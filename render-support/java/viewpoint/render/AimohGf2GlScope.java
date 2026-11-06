package viewpoint.render;

import org.lwjgl.opengl.*;

/** One boundary per source early/late phase. Only the actual touched slots. */
public final class AimohGf2GlScope implements AutoCloseable {
    private static final int[] UNITS=new int[59],SSBO_SLOTS={0,7,8,9,10,12,13},UBO_SLOTS={3,8,9,10,11,12,13};
    static{for(int i=0;i<32;i++)UNITS[i]=i;UNITS[32]=40;UNITS[33]=95;UNITS[34]=126;for(int i=0;i<14;i++)UNITS[35+i]=112+i;UNITS[49]=94;UNITS[50]=49;UNITS[51]=91;UNITS[52]=92;for(int i=0;i<6;i++)UNITS[53+i]=143+i;}
    private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),front=GL11.glGetInteger(GL11.GL_FRONT_FACE),cull=GL11.glGetInteger(GL11.GL_CULL_FACE_MODE),clearStencil=GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
    private final boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cullOn=GL11.glIsEnabled(GL11.GL_CULL_FACE),stencil=GL11.glIsEnabled(GL11.GL_STENCIL_TEST),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean offset=GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL),coverage=GL11.glIsEnabled(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE),clamp=GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
    private final float offsetFactor=GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR),offsetUnits=GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
    private final int[] viewport=new int[4],box=new int[4];private final double[] depthRange=new double[2];
    private final int[][] blend=new int[8][7],masks=new int[8][4],textures;private final boolean[] blendOn=new boolean[8];
    private final int[] stencilFront=new int[7],stencilBack=new int[7],ubos=new int[UBO_SLOTS.length],ssbos,image;
    private final long[] uboStart=new long[UBO_SLOTS.length],uboSize=new long[UBO_SLOTS.length],ssboStart,ssboSize;
    private final int arrayBuffer=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),uniformBuffer=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING),storageBuffer=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING),copyRead=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER),copyWrite=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
    private static final int[] GLASS_UNITS=java.util.stream.IntStream.concat(java.util.Arrays.stream(UNITS),java.util.stream.IntStream.concat(java.util.stream.IntStream.range(32,39),java.util.stream.IntStream.range(52,61))).toArray();
    private static final int[] GLASS_SLOTS={0,1,2,3,7,8,9,10,12,13};
    private final int[] units,slots;
    private final int indirectBuffer=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
    public AimohGf2GlScope(){this(false);}
    public AimohGf2GlScope(boolean glass){
        units=glass?GLASS_UNITS:UNITS;slots=glass?GLASS_SLOTS:SSBO_SLOTS;
        textures=new int[units.length][5];ssbos=new int[slots.length];ssboStart=new long[slots.length];ssboSize=new long[slots.length];image=new int[glass?18:6];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);GL11.glGetDoublev(GL11.GL_DEPTH_RANGE,depthRange);
        int[] sf={GL11.GL_STENCIL_FUNC,GL11.GL_STENCIL_REF,GL11.GL_STENCIL_VALUE_MASK,GL11.GL_STENCIL_WRITEMASK,GL11.GL_STENCIL_FAIL,GL11.GL_STENCIL_PASS_DEPTH_FAIL,GL11.GL_STENCIL_PASS_DEPTH_PASS};
        int[] sb={GL20.GL_STENCIL_BACK_FUNC,GL20.GL_STENCIL_BACK_REF,GL20.GL_STENCIL_BACK_VALUE_MASK,GL20.GL_STENCIL_BACK_WRITEMASK,GL20.GL_STENCIL_BACK_FAIL,GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL,GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS};
        for(int i=0;i<7;i++){stencilFront[i]=GL11.glGetInteger(sf[i]);stencilBack[i]=GL11.glGetInteger(sb[i]);}
        int[] blendKeys={GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA};
        for(int i=0;i<8;i++){blendOn[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);for(int k=0;k<6;k++)blend[i][k]=GL30.glGetIntegeri(blendKeys[k],i);GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,i,masks[i]);}
        for(int i=0;i<units.length;i++){int u=units[i];GL13.glActiveTexture(GL13.GL_TEXTURE0+u);textures[i][0]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);textures[i][1]=GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP);textures[i][2]=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);textures[i][3]=GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);textures[i][4]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,u);}
        GL13.glActiveTexture(active);
        for(int i=0;i<UBO_SLOTS.length;i++){int slot=UBO_SLOTS[i];ubos[i]=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,slot);uboStart[i]=GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_START,slot);uboSize[i]=GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_SIZE,slot);}
        for(int i=0;i<slots.length;i++)ssbos[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,slots[i]);
        for(int i=0;i<slots.length;i++){int slot=slots[i];ssboStart[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,slot);ssboSize[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,slot);}
        int[] imageKeys={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};for(int unit=0;unit<image.length/6;unit++)for(int i=0;i<6;i++)image[unit*6+i]=GL30.glGetIntegeri(imageKeys[i],unit);
    }
    private static void enable(int cap,boolean on){if(on)GL11.glEnable(cap);else GL11.glDisable(cap);}
    private static void stencil(int face,int[] s){GL20.glStencilFuncSeparate(face,s[0],s[1],s[2]);GL20.glStencilMaskSeparate(face,s[3]);GL20.glStencilOpSeparate(face,s[4],s[5],s[6]);}
    private static void buffer(int target,int slot,int id,long start,long size){if(id!=0&&size>0)GL30.glBindBufferRange(target,slot,id,start,size);else GL30.glBindBufferBase(target,slot,id);}
    @Override public void close(){
        for(int i=0;i<units.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+units[i]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i][0]);GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,textures[i][1]);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,textures[i][2]);GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,textures[i][3]);GL33.glBindSampler(units[i],textures[i][4]);}
        for(int i=0;i<UBO_SLOTS.length;i++)buffer(GL31.GL_UNIFORM_BUFFER,UBO_SLOTS[i],ubos[i],uboStart[i],uboSize[i]);for(int i=0;i<slots.length;i++)buffer(GL43.GL_SHADER_STORAGE_BUFFER,slots[i],ssbos[i],ssboStart[i],ssboSize[i]);for(int unit=0;unit<image.length/6;unit++){int off=unit*6;GL42.glBindImageTexture(unit,image[off],image[off+1],image[off+2]!=0,image[off+3],image[off+4],image[off+5]);}
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,arrayBuffer);GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,uniformBuffer);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storageBuffer);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,copyRead);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,copyWrite);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,indirectBuffer);GL30.glBindVertexArray(vao);GL20.glUseProgram(program);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL13.glActiveTexture(active);
        GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glScissor(box[0],box[1],box[2],box[3]);GL11.glDepthRange(depthRange[0],depthRange[1]);GL11.glDepthFunc(depthFunc);GL11.glDepthMask(depthMask);GL11.glFrontFace(front);GL11.glCullFace(cull);GL11.glClearStencil(clearStencil);stencil(GL11.GL_FRONT,stencilFront);stencil(GL11.GL_BACK,stencilBack);GL11.glPolygonOffset(offsetFactor,offsetUnits);
        for(int i=0;i<8;i++){if(blendOn[i])GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);GL40.glBlendFuncSeparatei(i,blend[i][0],blend[i][1],blend[i][2],blend[i][3]);GL40.glBlendEquationSeparatei(i,blend[i][4],blend[i][5]);GL30.glColorMaski(i,masks[i][0]!=0,masks[i][1]!=0,masks[i][2]!=0,masks[i][3]!=0);}
        enable(GL11.GL_DEPTH_TEST,depth);enable(GL11.GL_CULL_FACE,cullOn);enable(GL11.GL_STENCIL_TEST,stencil);enable(GL11.GL_SCISSOR_TEST,scissor);enable(GL11.GL_POLYGON_OFFSET_FILL,offset);enable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,coverage);enable(GL32.GL_DEPTH_CLAMP,clamp);
    }
}
