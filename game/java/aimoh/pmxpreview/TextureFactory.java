package aimoh.pmxpreview;
import aimoh.motion.PmxShading;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;
import viewpoint.render.Retirement;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;

/** Render-thread image reuse and bounded live bindings; no per-frame file reads. */
final class TextureFactory implements AutoCloseable {
    record Pixels(int width,int height,byte[] rgba) {}
    final LinkedHashMap<String,Texture> textures=new LinkedHashMap<>();
    private final LinkedHashMap<String,Pixels> images=new LinkedHashMap<>();
    private Texture[] bound;
    private long version=Long.MIN_VALUE,epoch=Long.MIN_VALUE;
    volatile int liveTextures,decodedImages;
    volatile long uploads;

    private static Pixels decode(String file)throws Exception {
        if(file.isBlank()||Files.isDirectory(Path.of(file)))return new Pixels(1,1,new byte[]{-1,-1,-1,-1});
        ByteBuffer encoded=null,pixels=null;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            byte[] bytes=Files.readAllBytes(Path.of(file));encoded=MemoryUtil.memAlloc(bytes.length);encoded.put(bytes).flip();
            IntBuffer w=stack.mallocInt(1),h=stack.mallocInt(1),c=stack.mallocInt(1);
            pixels=STBImage.stbi_load_from_memory(encoded,w,h,c,4);
            if(pixels==null)throw new IllegalArgumentException("贴图无法读取："+file+" "+STBImage.stbi_failure_reason());
            // STB frees memAddress(buffer), so copying must preserve its base position.
            byte[] rgba=new byte[Math.multiplyExact(Math.multiplyExact(w.get(0),h.get(0)),4)];pixels.get(0,rgba);
            return new Pixels(w.get(0),h.get(0),rgba);
        } finally {if(pixels!=null)STBImage.stbi_image_free(pixels);if(encoded!=null)MemoryUtil.memFree(encoded);}
    }
    private Texture bake(Pixels pixels,float[] material,int at,int marker,int[] ink)throws Exception {
        ByteBuffer output=MemoryUtil.memAlloc(pixels.rgba.length);
        TextureID id=null;
        try {
            for(int y=pixels.height-1;y>=0;y--)for(int x=0;x<pixels.width;x++)for(int k=0;k<4;k++) {
                float sample=Byte.toUnsignedInt(pixels.rgba[(y*pixels.width+x)*4+k])/255f;
                float value=Math.clamp(sample*material[at+4+k]+material[at+8+k],0,1)*material[at+k];
                output.put((byte)Math.round(Math.clamp(value,0,1)*255));
            }
            output.flip();
            id=new TextureID(pixels.width,pixels.height,0,GL11.GL_RGBA,GL11.GL_RGBA8);id.bindalways();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL11.GL_REPEAT);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL11.GL_REPEAT);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,pixels.width,pixels.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,output);
            if(marker>=0){
                int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
                try(MemoryStack stack=MemoryStack.stackPush()){
                    ByteBuffer corner=stack.malloc(16);for(int k=0;k<4;k++)corner.put((byte)17).put((byte)93).put((byte)(197+marker)).put((byte)251);corner.flip();
                    for(int y:new int[]{0,height-2})for(int x:new int[]{0,width-2})GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,x,y,2,2,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,corner);
                }
            }
            if(ink!=null){
                try(MemoryStack stack=MemoryStack.stackPush()){
                    ByteBuffer patch=stack.malloc(64);
                    for(int k=0;k<16;k++)for(int channel:ink)patch.put((byte)channel);
                    patch.flip();GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,3,3,4,4,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,patch);
                }
            }
            Texture result=new Texture(id,"aimoh-pmx:"+uploads);
            if(!result.isReady())throw new IllegalStateException("游戏贴图尚未就绪");
            uploads++;return result;
        } catch(Throwable e) {if(id!=null)id.destroy();throw e;}
        finally {MemoryUtil.memFree(output);}
    }
    Texture[] prepare(String[] files,float[] material,long nextVersion,long nextEpoch)throws Exception {return prepare(files,material,nextVersion,nextEpoch,null);}
    Texture[] prepare(String[] files,float[] material,long nextVersion,long nextEpoch,PmxShading shading)throws Exception {
        if(version==nextVersion&&epoch==nextEpoch)return bound;
        if(material.length!=files.length*12)throw new IllegalArgumentException("Material texture count differs");
        LinkedHashMap<String,Texture> next=new LinkedHashMap<>(),created=new LinkedHashMap<>();
        LinkedHashMap<String,Pixels> decoded=new LinkedHashMap<>();Texture[] result=new Texture[files.length];
        try {
            for(int i=0;i<files.length;i++) {
                String file=files[i];Pixels pixels=decoded.get(file);
                if(pixels==null&&epoch==nextEpoch)pixels=images.get(file);
                if(pixels==null)pixels=decode(file);decoded.put(file,pixels);
                int category=shading==null?-1:shading.kinds[i]==4?2:shading.kinds[i]==1||shading.kinds[i]==2?1:0;
                int marker=shading!=null&&shading.markerClearance[i]>3f/Math.min(pixels.width,pixels.height)?category:-1;
                int[] ink=shading!=null&&shading.outlineColors[i]!=null&&shading.markerClearance[i]>8f/Math.min(pixels.width,pixels.height)?shading.outlineColors[i]:null;
                String key=nextEpoch+"|"+file+"|"+Arrays.toString(Arrays.copyOfRange(material,i*12,i*12+12))+"|M"+marker+"|O"+Arrays.toString(ink);
                Texture texture=next.get(key);if(texture==null)texture=textures.get(key);
                if(texture==null){texture=bake(pixels,material,i*12,marker,ink);created.put(key,texture);}
                next.put(key,texture);result[i]=texture;
            }
        } catch(Throwable e) {for(Texture texture:created.values())texture.destroy();throw e;}
        for(var entry:textures.entrySet())if(!next.containsKey(entry.getKey()))Retirement.retireDrawing(entry.getValue()::destroy);
        textures.clear();textures.putAll(next);images.clear();images.putAll(decoded);
        version=nextVersion;epoch=nextEpoch;bound=result;liveTextures=textures.size();decodedImages=images.size();return result;
    }
    public void close() {for(Texture texture:textures.values())texture.destroy();textures.clear();images.clear();bound=null;liveTextures=decodedImages=0;}
}
