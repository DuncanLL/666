package viewpoint.render;

import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;

/** Eclipse depth producer -> original per-object shadow coordinates, no new map. */
public final class AimohGf2WorldShadow implements AutoCloseable {
    private static final String VERTEX="#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}";
    private final GlProgram program;
    private final int vao,sampler;
    public AimohGf2WorldShadow(String fragment){
        program=GlProgram.fromSources("GF2 native world depth bridge",new GlProgram.Sources(VERTEX,null,null,null,fragment),Map.of(),Map.of(),List.of());program.sampler("nativeWorldDepth",95);
        vao=GL30.glGenVertexArrays();sampler=GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
    }
    public void draw(int texture,int size,float[] sourceVp,float[] nativeVp,float depthBias){
        int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try{
            program.use();Matrix4f source=new Matrix4f().set(sourceVp),nativeMatrix=new Matrix4f().set(nativeVp);
            program.set("sourceVp",source);program.set("sourceInverse",new Matrix4f(source).invert());program.set("nativeVp",nativeMatrix);program.set("nativeInverse",new Matrix4f(nativeMatrix).invert());program.set("sourceSize",(float)size);
            program.set("sourceDepthBias",depthBias);
            GL13.glActiveTexture(GL13.GL_TEXTURE0+95);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(95,sampler);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthMask(true);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL30.glBindVertexArray(vao);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
        }finally{GL33.glBindSampler(95,0);GL13.glActiveTexture(active);GL30.glBindVertexArray(oldVao);GL20.glUseProgram(oldProgram);}
    }
    @Override public void close(){program.delete();GL30.glDeleteVertexArrays(vao);GL33.glDeleteSamplers(sampler);}
}
