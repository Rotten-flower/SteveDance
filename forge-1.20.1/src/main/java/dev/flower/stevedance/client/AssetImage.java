package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.flower.stevedance.media.CompactImages;
import net.minecraft.client.Minecraft;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** PNG and compact WebP assets share one upload path. PNG与小型WebP使用相同上传路径。 */
final class AssetImage {
    private AssetImage() {}
    static NativeImage read(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }
    static NativeImage read(InputStream input) throws IOException {
        byte[] bytes=input.readNBytes(32*1024*1024+1);
        if(bytes.length>32*1024*1024)throw new IOException("Texture exceeds byte budget");
        if(!CompactImages.isWebp(bytes))return NativeImage.read(new ByteArrayInputStream(bytes));
        byte[] decoded=CompactImages.decode(bytes, Minecraft.getInstance().gameDirectory.toPath().resolve("stevedance/cache"));
        ByteBuffer pixels=ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        int width=pixels.getInt(),height=pixels.getInt();
        NativeImage image=new NativeImage(width,height,false);
        try {
            for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setPixelRGBA(x,y,pixels.getInt());
            return image;
        } catch(RuntimeException ex){image.close();throw ex;}
    }
}
