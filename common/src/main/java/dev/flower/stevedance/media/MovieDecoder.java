package dev.flower.stevedance.media;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 原视频的单帧后台解码；不缓存整段图片，不在渲染线程执行 IO。
 * <p>Decodes one requested frame off-thread, with a single pending target and image.</p>
 */
public final class MovieDecoder implements AutoCloseable {
    public record Image(int frame, int width, int height, byte[] rgba) {}

    private final Object monitor = new Object();
    private final Thread worker;
    private final int frameCount;
    private volatile boolean closed;
    private volatile int requested = -1;
    private volatile Image latest;
    private volatile IOException failure;

    public MovieDecoder(Path source, Path cacheDirectory, double fps, int frameCount) {
        if (!Double.isFinite(fps) || fps <= 0 || fps > 60 || frameCount < 1 || frameCount > 36000) {
            throw new IllegalArgumentException("Invalid video clock");
        }
        this.frameCount = frameCount;
        worker = new Thread(() -> run(source, cacheDirectory, fps, frameCount), "stevedance-video");
        worker.setDaemon(true);
        worker.start();
    }

    public void request(int frame) {
        if (frame < 0 || frame >= frameCount) {
            throw new IllegalArgumentException("Video frame outside authored movie");
        }
        synchronized (monitor) {
            if (!closed && requested != frame) {
                requested = frame;
                monitor.notifyAll();
            }
        }
    }

    public Image poll(int wanted) throws IOException {
        if (failure != null) {
            throw failure;
        }
        Image image = latest;
        return image != null && image.frame == wanted ? image : null;
    }

    public Image current() throws IOException {
        if (failure != null) {
            throw failure;
        }
        return latest;
    }

    private void run(Path source, Path cacheDirectory, double fps, int count) {
        Path temporary = null;
        try {
            Path local = source;
            if (!source.getFileSystem().equals(FileSystems.getDefault())) {
                Files.createDirectories(cacheDirectory);
                temporary = Files.createTempFile(cacheDirectory, "movie-", ".mp4");
                // 中文：ZipFS 默认的 8 KiB 传输会使压缩 HDD 目录停滞。
                // EN: ZipFS's default 8 KiB transfer stalls compressed HDD directories.
                // 使用大块顺序读写，避免压缩目录中每个小块都触发磁盘操作。
                try (var input = Files.newInputStream(source);
                        var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int length;
                    while (!closed && (length = input.read(buffer)) != -1) {
                        output.write(buffer, 0, length);
                    }
                }
                local = temporary;
            }
            if (closed) {
                return;
            }
            MovieNative.prepare(
                    cacheDirectory.toAbsolutePath().resolveSibling("video-native-sd1"));
            if (closed) {
                return;
            }
            try (Reader reader = new Reader(local, fps, count)) {
                while (!closed) {
                    int target;
                    synchronized (monitor) {
                        while (!closed
                                && (requested < 0 || latest != null && latest.frame == requested)) {
                            monitor.wait();
                        }
                        if (closed) {
                            break;
                        }
                        target = requested;
                    }
                    Image image = reader.read(target);
                    // 新的跳转已经到达时丢弃旧结果；不能把上一段画面贴到新切点。
                    // 中文：切勿将已取代的seek作为当前帧发布。
                    // EN: Never publish a superseded seek as the current frame.
                    synchronized (monitor) {
                        if (!closed && requested == target) {
                            latest = image;
                        }
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError ex) {
            if (!closed) {
                failure =
                        ex instanceof IOException io
                                ? io
                                : new IOException("Cannot decode PV movie", ex);
            }
        } finally {
            latest = null;
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    System.getLogger(MovieDecoder.class.getName())
                            .log(System.Logger.Level.WARNING, "Cannot remove video cache", ex);
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            latest = null;
            monitor.notifyAll();
        }
        worker.interrupt();
    }

    /**
     * 原生状态由单一所有者负责，关闭操作不与原生解码并发。
     * <p>One owner controls all native state; close never races a native decode.</p>
     */
    static final class Reader implements AutoCloseable {
        private long handle;
        Reader(Path source, double fps, int count) throws IOException {
            handle = MovieNative.open(source.toAbsolutePath().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8), fps, count);
        }
        Image read(int target) throws IOException {
            if (handle == 0) throw new IOException("Movie reader is closed");
            byte[] data = MovieNative.read(handle, target);
            var buffer = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int width = buffer.getInt(), height = buffer.getInt();
            return new Image(target, width, height, java.util.Arrays.copyOfRange(data, 8, data.length));
        }
        @Override public void close() {
            if (handle != 0) { MovieNative.close(handle); handle = 0; }
        }
    }
}
