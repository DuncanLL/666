package aimoh.pmxpreview;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import viewpoint.platform.GlProgram;
import viewpoint.platform.ShaderPacks;
import zombie.ZomboidFileSystem;

/** One immutable 16 MiB data texture; no per-frame image upload or new render pass. */
public final class NativePmxMaterials {
    public static final int UNIT=96;
    private static Field programField;
    private static ShaderPacks.Active loadedPack;
    private static int texture;
    private static int oldTexture,oldSampler;
    private static boolean bound,failed;

    public static void bind(Object pass) {
        if(bound)throw new IllegalStateException("Nested Native model pass");
        try {
            if(programField==null){programField=pass.getClass().getDeclaredField("gbuffer");programField.setAccessible(true);}
            GlProgram program=(GlProgram)programField.get(pass);
            if(program.location("uPmxDataReady")<0){release();return;}
            program.sampler("uPmxMaterials",UNIT);
            program.setInt("uPmxDataReady",0);
            ShaderPacks.Active active=ShaderPacks.drawn();
            if(active!=loadedPack){release();loadedPack=active;failed=false;}
            if(failed||active==null)return;
            if(texture==0){
                Path path=Path.of(ZomboidFileSystem.instance.getCacheDir(),"viewpoint-shaderpacks",active.pack.id,"shaders","textures","PmxMaterials.png");
                texture=upload(path);
            }
            int oldUnit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0+UNIT);
            oldTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            oldSampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(UNIT,0);
            GL13.glActiveTexture(oldUnit);bound=true;
            program.setInt("uPmxDataReady",1);
        } catch(Exception failure) {
            failed=true;System.err.println("[AimohPMX] Native material input unavailable; original model shading retained: "+failure);
        }
    }

    public static void unbind() {
        if(!bound)return;
        int oldUnit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTexture);GL33.glBindSampler(UNIT,oldSampler);
        GL13.glActiveTexture(oldUnit);bound=false;
    }

    private static int upload(Path path)throws Exception {
        byte[] bytes=Files.readAllBytes(path);ByteBuffer input=MemoryUtil.memAlloc(bytes.length),pixels=null;
        int oldUnit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),id=0;
        GL13.glActiveTexture(GL13.GL_TEXTURE0+UNIT);
        int oldBinding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try(MemoryStack stack=MemoryStack.stackPush()){
            input.put(bytes).flip();var width=stack.mallocInt(1);var height=stack.mallocInt(1);var components=stack.mallocInt(1);
            pixels=STBImage.stbi_load_from_memory(input,width,height,components,4);
            if(pixels==null||width.get(0)!=2048||height.get(0)!=2048)throw new IllegalArgumentException("Expected 2048x2048 PMX material page");
            id=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,0x812F);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,0x812F);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,2048,2048,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            int error=GL11.glGetError();if(error!=GL11.GL_NO_ERROR)throw new IllegalStateException("Material upload GL error "+error);
            return id;
        } catch(Exception error){if(id!=0)GL11.glDeleteTextures(id);throw error;}
        finally {if(pixels!=null)STBImage.stbi_image_free(pixels);MemoryUtil.memFree(input);GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldBinding);GL13.glActiveTexture(oldUnit);}
    }

    private static void release(){if(texture!=0){GL11.glDeleteTextures(texture);texture=0;}loadedPack=null;}
}
