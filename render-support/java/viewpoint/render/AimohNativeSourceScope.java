package viewpoint.render;
import org.lwjgl.opengl.*;

/** Native-only cloud, spot UBO and cube-sampling state absent from the reused draw scope. */
final class AimohNativeSourceScope implements AutoCloseable {
    static void forget(viewpoint.platform.GlProgram... owned){
        try{var field=FrameUniforms.class.getDeclaredField("declares");field.setAccessible(true);var cache=(java.util.Map<?,?>)field.get(null);for(var program:owned)if(program!=null)cache.remove(program);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Native owned-program metadata release is unavailable",e);}
    }
    private final AimohGf2GlScope shared;
    private final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),cloud,noise,sampler;
    private final boolean seamless=GL11.glIsEnabled(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
    private final int spots=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,2);
    private final int uniformBuffer=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
    private final long spotsStart=GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_START,2),spotsSize=GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_SIZE,2);
    AimohNativeSourceScope(){this(false);}
    AimohNativeSourceScope(boolean geometry){
        shared=new AimohGf2GlScope(geometry,3);
        GL11.glEnable(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+130);cloud=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,130);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+18);noise=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);GL13.glActiveTexture(active);
    }
    static int[] bindings(){
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+18);int noise=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);GL13.glActiveTexture(GL13.GL_TEXTURE0+130);int cloud=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,130);GL13.glActiveTexture(active);
        int[] result=new int[24];result[0]=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,0);result[1]=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,2);result[2]=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);result[3]=noise;result[4]=cloud;result[5]=sampler;
        int[] keys={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};
        for(int unit=0;unit<3;unit++)for(int k=0;k<6;k++)result[6+unit*6+k]=GL30.glGetIntegeri(keys[k],unit);
        return result;
    }
    @Override public void close(){
        shared.close();GL13.glActiveTexture(GL13.GL_TEXTURE0+130);GL11.glBindTexture(GL11.GL_TEXTURE_2D,cloud);GL33.glBindSampler(130,sampler);
        if(spots!=0&&spotsSize>0)GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER,2,spots,spotsStart,spotsSize);else GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,2,spots);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,uniformBuffer);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+18);GL11.glBindTexture(GL12.GL_TEXTURE_3D,noise);GL13.glActiveTexture(active);
        if(!seamless)GL11.glDisable(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
    }
}
