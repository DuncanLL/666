package viewpoint.render;
import org.lwjgl.opengl.*;

/** Explicit state preservation for a core-profile context without an attribute stack. */
final class Gfl2FixedState {
    private final int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),front=GL11.glGetInteger(GL11.GL_FRONT_FACE),cull=GL11.glGetInteger(GL11.GL_CULL_FACE_MODE),clearStencil=GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
    private final boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cullOn=GL11.glIsEnabled(GL11.GL_CULL_FACE),stencil=GL11.glIsEnabled(GL11.GL_STENCIL_TEST),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean offset=GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL),coverage=GL11.glIsEnabled(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE),clamp=GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
    private final float offsetFactor=GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR),offsetUnits=GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
    private final int[] viewport=new int[4],box=new int[4];private final double[] depthRange=new double[2];
    private final int[][] blend=new int[8][7],masks=new int[8][4];private final boolean[] blendOn=new boolean[8];
    private final int[] stencilFront=new int[7],stencilBack=new int[7];
    private final boolean defaultDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==0;
    private final int[] drawBuffers=new int[defaultDraw?1:GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
    Gfl2FixedState(){
        for(int i=0;i<drawBuffers.length;i++)drawBuffers[i]=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);GL11.glGetDoublev(GL11.GL_DEPTH_RANGE,depthRange);
        int[] sf={GL11.GL_STENCIL_FUNC,GL11.GL_STENCIL_REF,GL11.GL_STENCIL_VALUE_MASK,GL11.GL_STENCIL_WRITEMASK,GL11.GL_STENCIL_FAIL,GL11.GL_STENCIL_PASS_DEPTH_FAIL,GL11.GL_STENCIL_PASS_DEPTH_PASS};
        int[] sb={GL20.GL_STENCIL_BACK_FUNC,GL20.GL_STENCIL_BACK_REF,GL20.GL_STENCIL_BACK_VALUE_MASK,GL20.GL_STENCIL_BACK_WRITEMASK,GL20.GL_STENCIL_BACK_FAIL,GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL,GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS};
        for(int i=0;i<7;i++){stencilFront[i]=GL11.glGetInteger(sf[i]);stencilBack[i]=GL11.glGetInteger(sb[i]);}
        int[] blendKeys={GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA};
        for(int i=0;i<8;i++){blendOn[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);for(int k=0;k<6;k++)blend[i][k]=GL30.glGetIntegeri(blendKeys[k],i);GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,i,masks[i]);}
    }
    private static void enable(int cap,boolean on){if(on)GL11.glEnable(cap);else GL11.glDisable(cap);}
    private static void stencil(int face,int[] s){GL20.glStencilFuncSeparate(face,s[0],s[1],s[2]);GL20.glStencilMaskSeparate(face,s[3]);GL20.glStencilOpSeparate(face,s[4],s[5],s[6]);}
    void restore(){
        if(defaultDraw)GL11.glDrawBuffer(drawBuffers[0]);else GL20.glDrawBuffers(drawBuffers);
        GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glScissor(box[0],box[1],box[2],box[3]);GL11.glDepthRange(depthRange[0],depthRange[1]);GL11.glDepthFunc(depthFunc);GL11.glDepthMask(depthMask);GL11.glFrontFace(front);GL11.glCullFace(cull);GL11.glClearStencil(clearStencil);stencil(GL11.GL_FRONT,stencilFront);stencil(GL11.GL_BACK,stencilBack);GL11.glPolygonOffset(offsetFactor,offsetUnits);
        for(int i=0;i<8;i++){if(blendOn[i])GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);GL40.glBlendFuncSeparatei(i,blend[i][0],blend[i][1],blend[i][2],blend[i][3]);GL40.glBlendEquationSeparatei(i,blend[i][4],blend[i][5]);GL30.glColorMaski(i,masks[i][0]!=0,masks[i][1]!=0,masks[i][2]!=0,masks[i][3]!=0);}
        enable(GL11.GL_DEPTH_TEST,depth);enable(GL11.GL_CULL_FACE,cullOn);enable(GL11.GL_STENCIL_TEST,stencil);enable(GL11.GL_SCISSOR_TEST,scissor);enable(GL11.GL_POLYGON_OFFSET_FILL,offset);enable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,coverage);enable(GL32.GL_DEPTH_CLAMP,clamp);
    }
}
