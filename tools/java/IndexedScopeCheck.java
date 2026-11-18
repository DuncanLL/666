package viewpoint.render;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import viewpoint.platform.*;
public final class IndexedScopeCheck{
    public static void main(String[] args){
        if(!GLFW.glfwInit())throw new AssertionError();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);long w=GLFW.glfwCreateWindow(16,16,"scope",0,0);
        GLFW.glfwMakeContextCurrent(w);GL.createCapabilities();
        for(int i=0;i<8;i++){
            if(i%2==0)GL30.glEnablei(GL11.GL_BLEND,i);
            GL40.glBlendFuncSeparatei(i,i%2==0?GL11.GL_DST_COLOR:GL11.GL_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ONE);
            GL30.glColorMaski(i,true,i%2==0,false,true);
        }
        int bind=GL45.glCreateBuffers();GL45.glNamedBufferData(bind,4096L,GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER,9,bind,256,512);
        GL11.glEnable(GL32.GL_DEPTH_CLAMP);
        GL11.glEnable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
        int sampler=GL33.glGenSamplers();for(int i=64;i<80;i++)GL33.glBindSampler(i,sampler);
        int arrayTex=GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        int bufferTex=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
        for(var phase:Gfl2GlScope.Phase.values()){
            try(var scope=new Gfl2GlScope(phase)){
                GL11.glDisable(GL11.GL_BLEND);GL11.glColorMask(false,true,false,true);GL14.glBlendFuncSeparate(1,0,1,0);
                if(phase==Gfl2GlScope.Phase.PROBE_GEOMETRY){
                    GL45.glBindTextureUnit(41,arrayTex);GL33.glBindSampler(41,sampler);GL45.glBindTextureUnit(136,arrayTex);
                    for(int unit:new int[]{48,88,89,90})GL45.glBindTextureUnit(unit,bufferTex);
                }
                GL11.glDisable(GL32.GL_DEPTH_CLAMP);GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
                if(phase!=Gfl2GlScope.Phase.PROBE_GEOMETRY && phase!=Gfl2GlScope.Phase.PROBE_LIGHT){
                    GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,9,0);
                    for(int i=64;i<80;i++)GL33.glBindSampler(i,0);
                }
            }
        }
        if(GL11.glGetError()!=0)throw new AssertionError("GL");
        GL11.glDeleteTextures(arrayTex);GL11.glDeleteTextures(bufferTex);
        GL33.glDeleteSamplers(sampler);GL15.glDeleteBuffers(bind);GLFW.glfwDestroyWindow(w);GLFW.glfwTerminate();
        System.out.println("INDEXED_STATE_AND_RANGES_RESTORED");
    }
}
