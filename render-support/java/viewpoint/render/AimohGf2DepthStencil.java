package viewpoint.render;

import java.util.*;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;

/** Private source-character depth/stencil target; native world targets stay intact.
 * This GPU rejects D32F depth plus a separate S8 attachment. A packed D32F/S8
 * texture is required for the original hair/face stencil operations.
 */
public final class AimohGf2DepthStencil implements AutoCloseable {
    private static final String VERTEX="#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}";
    private static final String FRAGMENT="#version 430\nuniform sampler2D sourceDepth;void main(){gl_FragDepth=texelFetch(sourceDepth,ivec2(gl_FragCoord.xy),0).r;}";
    private final GlProgram copy;
    private final int vao;
    private int depth,framebuffer,width,height,sourceNormal,sourceMotion;
    private final int motionProgram,motionViewport;
    public AimohGf2DepthStencil(){
        this(null);
    }
    public AimohGf2DepthStencil(String motionSource){
        copy=GlProgram.fromSources("GF2 source depth copy",new GlProgram.Sources(VERTEX,null,null,null,FRAGMENT),Map.of(),Map.of(),List.of());
        copy.sampler("sourceDepth",0);vao=GL30.glGenVertexArrays();
        motionProgram=motionSource==null?0:AimohGf2EclipseEnvironment.compileCompute(motionSource);
        motionViewport=motionProgram==0?-1:GL20.glGetUniformLocation(motionProgram,"sourceViewport");
        if(motionProgram!=0){int old=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);GL20.glUseProgram(motionProgram);GL20.glUniform1i(GL20.glGetUniformLocation(motionProgram,"sourceMotion"),0);GL20.glUseProgram(old);}
    }
    public int depthTexture(){return depth;}
    public int framebuffer(){return framebuffer;}
    public int sourceNormal(){return sourceNormal;}
    public int sourceMotion(){return sourceMotion;}
    public void ensureOriginalOutputs(){
        if(width<=0||height<=0)throw new IllegalStateException("Source output size missing");
        if(sourceNormal!=0)return;
        int old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        sourceNormal=outputTexture(GL30.GL_RGBA16F);sourceMotion=outputTexture(GL30.GL_RG16F);GL11.glBindTexture(GL11.GL_TEXTURE_2D,old);
    }
    private int outputTexture(int format){
        int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL42.glTexStorage2D(GL11.GL_TEXTURE_2D,1,format,width,height);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return texture;
    }
    public void decodeMotion(int nativeTarget){
        if(sourceMotion==0||motionProgram==0||nativeTarget<=0)throw new IllegalStateException("Motion resources missing");
        GL20.glUseProgram(motionProgram);GL20.glUniform2f(motionViewport,width,height);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,sourceMotion);GL33.glBindSampler(0,0);
        GL42.glBindImageTexture(0,nativeTarget,0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_RG16F);GL43.glDispatchCompute((width+15)/16,(height+15)/16,1);GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }
    public long extraBytes(){return (long)width*height*(sourceNormal==0?8:20);}
    public void ensure(int w,int h){
        if(w<=0||h<=0)throw new IllegalArgumentException("Invalid source depth dimensions");
        if(w==width&&h==height)return;
        deleteImages();width=w;height=h;depth=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D,1,GL30.GL_DEPTH32F_STENCIL8,w,h);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);
        framebuffer=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_STENCIL_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
        GL11.glDrawBuffer(GL11.GL_NONE);GL11.glReadBuffer(GL11.GL_NONE);check();
    }
    /** Source output index -> actual target texture. No implicit attachment names. */
    public void targets(Map<Integer,Integer> colors){
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);int maximum=-1;
        for(int i=0;i<8;i++){
            Integer texture=colors.get(i);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0+i,GL11.GL_TEXTURE_2D,texture==null?0:texture,0);
            if(texture!=null)maximum=i;
        }
        if(maximum<0)GL11.glDrawBuffer(GL11.GL_NONE);
        else {int[] draw=new int[maximum+1];Arrays.fill(draw,GL11.GL_NONE);for(int i:colors.keySet())draw[i]=GL30.GL_COLOR_ATTACHMENT0+i;GL20.glDrawBuffers(draw);}
        check();
    }
    public void begin(int nativeDepth){
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glViewport(0,0,width,height);
        clearActorStencil();
        copyDepth(nativeDepth);
    }
    /** New actor keeps existing depth occlusion but receives a clean stencil group. */
    public void clearActorStencil(){
        boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try{GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glStencilMask(255);GL11.glClearStencil(0);GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);}
        finally{enable(GL11.GL_SCISSOR_TEST,scissor);}
    }
    public void writeBackDepth(int nativeFramebuffer){GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,nativeFramebuffer);copyDepth(depth);}
    private void copyDepth(int source){
        if(source<=0)throw new IllegalArgumentException("Actual source depth is required");
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        // This scope is enclosed by the character phase's full state restoration.
        // Restore local draw essentials so this operation cannot tint HDR color.
        boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),stencil=GL11.glIsEnabled(GL11.GL_STENCIL_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);int func=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthMask(true);GL11.glColorMask(false,false,false,false);
            copy.use();GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,source);GL33.glBindSampler(0,0);
            GL30.glBindVertexArray(vao);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        } finally {
            GL30.glBindVertexArray(oldVao);GL20.glUseProgram(program);GL11.glDepthFunc(func);GL11.glDepthMask(depthMask);GL11.glColorMask(true,true,true,true);
            enable(GL11.GL_BLEND,blend);enable(GL11.GL_STENCIL_TEST,stencil);enable(GL11.GL_CULL_FACE,cull);enable(GL11.GL_SCISSOR_TEST,scissor);
        }
    }
    private static void enable(int capability,boolean on){if(on)GL11.glEnable(capability);else GL11.glDisable(capability);}
    private static void check(){int value=GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);if(value!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("GF2 packed source FBO incomplete: "+Integer.toHexString(value));}
    private void deleteImages(){if(framebuffer!=0)GL30.glDeleteFramebuffers(framebuffer);if(depth!=0)GL11.glDeleteTextures(depth);if(sourceNormal!=0)GL11.glDeleteTextures(sourceNormal);if(sourceMotion!=0)GL11.glDeleteTextures(sourceMotion);framebuffer=depth=sourceNormal=sourceMotion=0;}
    @Override public void close(){deleteImages();GL30.glDeleteVertexArrays(vao);copy.delete();if(motionProgram!=0)GL20.glDeleteProgram(motionProgram);}
}
