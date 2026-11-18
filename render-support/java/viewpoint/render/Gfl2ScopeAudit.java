package viewpoint.render;

import java.util.*;
import org.lwjgl.opengl.*;

/** Diagnostic only. Every texture unit and indexed attachment is compared, including untouched slots. */
final class Gfl2ScopeAudit {
    private final Map<String,Long> before=snapshot();
    void verify(String phase) {
        Map<String,Long> after=snapshot();
        List<String> changed=new ArrayList<>();
        for(var entry:before.entrySet()){
            Long current=after.get(entry.getKey());
            if(!entry.getValue().equals(current))changed.add(entry.getKey()+"="+entry.getValue()+"->"+current);
        }
        if(!changed.isEmpty())throw new IllegalStateException("GL state changed after "+phase+": "+changed);
    }
    private static Map<String,Long> snapshot(){
        Map<String,Long> state=new LinkedHashMap<>();
        int[] simple={GL20.GL_CURRENT_PROGRAM,GL30.GL_VERTEX_ARRAY_BINDING,GL30.GL_DRAW_FRAMEBUFFER_BINDING,
            GL30.GL_READ_FRAMEBUFFER_BINDING,GL13.GL_ACTIVE_TEXTURE,GL11.GL_DEPTH_FUNC,GL11.GL_DEPTH_WRITEMASK,
            GL11.GL_FRONT_FACE,GL11.GL_CULL_FACE_MODE,GL11.GL_STENCIL_CLEAR_VALUE,GL11.GL_STENCIL_FUNC,
            GL11.GL_STENCIL_REF,GL11.GL_STENCIL_VALUE_MASK,GL11.GL_STENCIL_WRITEMASK,GL11.GL_STENCIL_FAIL,
            GL11.GL_STENCIL_PASS_DEPTH_FAIL,GL11.GL_STENCIL_PASS_DEPTH_PASS,GL20.GL_STENCIL_BACK_FUNC,GL20.GL_STENCIL_BACK_REF,
            GL20.GL_STENCIL_BACK_VALUE_MASK,GL20.GL_STENCIL_BACK_WRITEMASK,GL20.GL_STENCIL_BACK_FAIL,
            GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL,GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS,GL15.GL_ARRAY_BUFFER_BINDING,
            GL31.GL_UNIFORM_BUFFER_BINDING,GL43.GL_SHADER_STORAGE_BUFFER_BINDING,GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,
            GL40.GL_DRAW_INDIRECT_BUFFER_BINDING,GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING,GL21.GL_PIXEL_UNPACK_BUFFER_BINDING,GL11.GL_READ_BUFFER};
        for(int key:simple)state.put("state/"+key,(long)GL11.glGetInteger(key));
        for(int key:new int[]{GL11.GL_DEPTH_TEST,GL11.GL_CULL_FACE,GL11.GL_STENCIL_TEST,GL11.GL_SCISSOR_TEST,
             GL11.GL_POLYGON_OFFSET_FILL,GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,GL32.GL_DEPTH_CLAMP,GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS})
            state.put("enabled/"+key,GL11.glIsEnabled(key)?1L:0L);
        for(int key:new int[]{GL11.GL_POLYGON_OFFSET_FACTOR,GL11.GL_POLYGON_OFFSET_UNITS})
            state.put("float/"+key,(long)Float.floatToRawIntBits(GL11.glGetFloat(key)));
        for(int key:new int[]{GL11.GL_VIEWPORT,GL11.GL_SCISSOR_BOX}){
            int[] values=new int[4];GL11.glGetIntegerv(key,values);
            for(int j=0;j<4;j++)state.put("rect/"+key+"/"+j,(long)values[j]);
        }
        double[] range=new double[2];GL11.glGetDoublev(GL11.GL_DEPTH_RANGE,range);
        for(int j=0;j<2;j++)state.put("depthRange/"+j,Double.doubleToRawLongBits(range[j]));
        int max=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        for(int i=0;i<max;i++)for(int key:new int[]{GL11.GL_TEXTURE_BINDING_2D,GL13.GL_TEXTURE_BINDING_CUBE_MAP,
            GL31.GL_TEXTURE_BINDING_BUFFER,GL30.GL_TEXTURE_BINDING_2D_ARRAY,GL12.GL_TEXTURE_BINDING_3D,GL11.GL_TEXTURE_BINDING_1D,GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY,GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE,GL33.GL_SAMPLER_BINDING})
            state.put("texture/"+i+"/"+key,(long)GL30.glGetIntegeri(key,i));
        for(int i=0;i<GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);i++){
            state.put("draw/"+i,(long)GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i));state.put("blend/"+i,GL30.glIsEnabledi(GL11.GL_BLEND,i)?1L:0L);
            int[] mask=new int[4];GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,i,mask);
            for(int j=0;j<4;j++)state.put("mask/"+i+"/"+j,(long)mask[j]);
            for(int key:new int[]{GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA,
                                  GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA})
                state.put("blend/"+i+"/"+key,(long)GL30.glGetIntegeri(key,i));
        }
        for(int[] b:new int[][]{{GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS,GL31.GL_UNIFORM_BUFFER_BINDING,GL31.GL_UNIFORM_BUFFER_START,GL31.GL_UNIFORM_BUFFER_SIZE},
            {GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,GL43.GL_SHADER_STORAGE_BUFFER_BINDING,GL43.GL_SHADER_STORAGE_BUFFER_START,GL43.GL_SHADER_STORAGE_BUFFER_SIZE}}){
            int count=GL11.glGetInteger(b[0]);for(int i=0;i<count;i++)for(int j=1;j<=3;j++)state.put("buffer/"+b[j]+"/"+i,GL32.glGetInteger64i(b[j],i));
        }
        int images=GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS);
        for(int i=0;i<images;i++)for(int key:new int[]{GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT})
            state.put("image/"+i+"/"+key,(long)GL30.glGetIntegeri(key,i));
        return state;
    }
}
