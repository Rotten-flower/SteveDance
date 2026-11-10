package dev.flower.stevedance.media;



import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Extract pinned native files once; Forge union URLs cannot be used as DLL cache identities.
 * 先核对并提取原库，再按真实路径加载，避免覆盖已加载的 DLL。 */
final class MovieNative {
    private static boolean prepared;

    static synchronized void prepare(Path cache) throws IOException {
        if (prepared) {
            return;
        }
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        if (!os.startsWith("Windows") || !java.util.Set.of("amd64", "x86_64").contains(arch))
            throw new IOException("Video decoder requires Windows x64");
        String platform = "windows-x86_64";
        Path directory = cache.resolve(platform);
        Files.createDirectories(directory);
        int count = 0;
        try (var stream =
                MovieNative.class.getResourceAsStream("/META-INF/stevedance-video-native.list")) {
            if (stream == null) {
                throw new IOException("Missing movie native manifest");
            }
            try (var reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    String[] parts = line.split("\t");
                    if (parts.length != 4) {
                        throw new IOException("Invalid movie native manifest");
                    }
                    if (!parts[0].equals(platform)) {
                        continue;
                    }
                    String name = parts[1].substring(parts[1].lastIndexOf('/') + 1);
                    Path target = directory.resolve(name);
                    long size = Long.parseLong(parts[2]);
                    if (!Files.isRegularFile(target)
                            || Files.size(target) != size
                            || !digest(target).equals(parts[3])) {
                        Path temp = Files.createTempFile(directory, "native-", ".tmp");
                        try {
                            try (var input = MovieNative.class.getResourceAsStream('/' + parts[1]);
                                    var output = Files.newOutputStream(temp)) {
                                if (input == null) {
                                    throw new IOException("Missing movie library " + name);
                                }
                                byte[] buffer = new byte[1024 * 1024];
                                for (int n; (n = input.read(buffer)) != -1; ) {
                                    output.write(buffer, 0, n);
                                }
                            }
                            if (Files.size(temp) != size || !digest(temp).equals(parts[3])) {
                                throw new IOException("Movie library checksum mismatch " + name);
                            }
                            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(temp);
                        }
                    }
                    count++;
                }
            }
        }
        if (count == 0) {
            throw new IOException("Unsupported movie decoder platform " + platform);
        }
        System.load(directory.resolve("stevedance-media.dll").toAbsolutePath().toString());
        prepared = true;
    }

    static native long open(byte[] utf8Path, double fps, int count) throws IOException;
    static native byte[] read(long handle, int target) throws IOException;
    static native void close(long handle);
    static native byte[] decodeImage(byte[] data) throws IOException;

    private static String digest(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            for (int n; (n = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}
