package viewpoint.render;
import org.lwjgl.opengl.*;

/** Native-only cloud, spot UBO and cube-sampling state absent from the reused draw scope. */
final class Gfl2NativeSourceScope implements AutoCloseable {
    static void forget(viewpoint.platform.GlProgram... owned){
        try{var field=FrameUniforms.class.getDeclaredField("declares");field.setAccessible(true);var cache=(java.util.Map<?,?>)field.get(null);for(var program:owned)if(program!=null)cache.remove(program);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Native owned-program metadata release is unavailable",e);}
    }
    private final Gfl2GlScope shared;
    Gfl2NativeSourceScope(){this(Gfl2GlScope.Phase.COLOUR);}
    Gfl2NativeSourceScope(Gfl2GlScope.Phase phase){
        shared=new Gfl2GlScope(phase);
        GL11.glEnable(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
    }
    static int[] bindings(){
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+18);int noise=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);GL13.glActiveTexture(GL13.GL_TEXTURE0+130);int cloud=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,130);GL13.glActiveTexture(active);
        int[] result=new int[30];result[0]=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,0);result[1]=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,2);result[2]=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);result[3]=noise;result[4]=cloud;result[5]=sampler;
        int[] keys={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};
        for(int unit=0;unit<4;unit++)for(int k=0;k<6;k++)result[6+unit*6+k]=GL30.glGetIntegeri(keys[k],unit);
        return result;
    }
    @Override public void close(){shared.close();}
}
