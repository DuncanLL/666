package aimoh.motion;

import java.nio.ByteOrder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/** Packs a sampled PMX pose into Viewpoint's existing dynamic model buffers. */
final class PmxMeshStream implements AutoCloseable {
    private static final int STORAGE=GL43.GL_SHADER_STORAGE_BUFFER;
    private static final String COMPUTE="""
        #version 430
        layout(local_size_x=128) in;
        layout(std430,binding=0) readonly buffer Position {float positions[];};
        layout(std430,binding=1) readonly buffer Normal {float normals[];};
        layout(std430,binding=2) readonly buffer Sources {uint sourceVertices[];};
        layout(std430,binding=3) buffer Destination {float words[];};
        uniform uint vertexCount;
        uniform bool continuous;
        void main() {
            uint i=gl_GlobalInvocationID.x;
            if(i>=vertexCount)return;
            uint dst=i*19u,src=sourceVertices[i]*3u;
            for(uint k=0u;k<3u;k++) {
                float position=positions[src+k];
                words[dst+11u+k]=continuous?words[dst+k]:position;
                words[dst+k]=position;
                words[dst+3u+k]=normals[src+k];
            }
        }
        """;

    private final int program,staging,alignment,vertexCount,continuous;
    private final int[] bindings=new int[4];
    private final long[] offsets=new long[4],sizes=new long[4];
    private int oldProgram,oldStorage;

    static PmxMeshStream create() {
        return GL.getCapabilities().OpenGL43 ? new PmxMeshStream() : null;
    }

    private PmxMeshStream() {
        program=GpuDeformation.link(COMPUTE);
        staging=GL15.glGenBuffers();
        alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        vertexCount=GL20.glGetUniformLocation(program,"vertexCount");
        continuous=GL20.glGetUniformLocation(program,"continuous");
        if(staging==0||alignment<=0||vertexCount<0||continuous<0) {
            close();
            throw new IllegalStateException("Incomplete Viewpoint mesh streaming resources");
        }
    }

    void begin(float[] points,float[] directions) {
        oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        oldStorage=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for(int i=0;i<bindings.length;i++) {
            bindings[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            offsets[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        long bytes=(long)points.length*Float.BYTES;
        long normalOffset=((bytes+alignment-1)/alignment)*alignment;
        long total=normalOffset+bytes;
        GL15.glBindBuffer(STORAGE,staging);
        // Orphan storage so queued draws can keep using the previous allocation.
        GL15.glBufferData(STORAGE,total,GL15.GL_STREAM_DRAW);
        var mapped=GL30.glMapBufferRange(STORAGE,0,total,GL30.GL_MAP_WRITE_BIT|GL30.GL_MAP_UNSYNCHRONIZED_BIT);
        if(mapped==null)throw new IllegalStateException("Cannot map Viewpoint mesh input");
        try {
            var values=mapped.order(ByteOrder.nativeOrder()).asFloatBuffer();
            values.put(0,points);
            values.put(Math.toIntExact(normalOffset/Float.BYTES),directions);
        } finally {
            if(!GL15.glUnmapBuffer(STORAGE))throw new IllegalStateException("Viewpoint mesh input storage lost");
        }
        GL30.glBindBufferRange(STORAGE,0,staging,0,bytes);
        GL30.glBindBufferRange(STORAGE,1,staging,normalOffset,bytes);
        GL20.glUseProgram(program);
    }

    static int remap(int[] source) {
        int remap=GL15.glGenBuffers();
        if(remap==0)throw new IllegalStateException("Viewpoint vertex map allocation failed");
        try {
            var indices=BufferUtils.createIntBuffer(source.length);
            indices.put(source).flip();
            GL15.glBindBuffer(STORAGE,remap);
            GL15.glBufferData(STORAGE,indices,GL15.GL_STATIC_DRAW);
            return remap;
        } catch(RuntimeException|LinkageError failure) {
            GL15.glDeleteBuffers(remap);
            throw failure;
        }
    }

    void pack(int destination,int remap,int count,boolean history) {
        GL30.glBindBufferBase(STORAGE,2,remap);
        GL30.glBindBufferBase(STORAGE,3,destination);
        GL30.glUniform1ui(vertexCount,count);
        GL20.glUniform1i(continuous,history?1:0);
        GL43.glDispatchCompute((count+127)/128,1,1);
    }

    void end() {
        // VP copies the output VBO into its model arena before any of the draw passes run.
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        for(int i=0;i<bindings.length;i++) {
            if(bindings[i]!=0&&sizes[i]>0)GL30.glBindBufferRange(STORAGE,i,bindings[i],offsets[i],sizes[i]);
            else GL30.glBindBufferBase(STORAGE,i,bindings[i]);
        }
        GL15.glBindBuffer(STORAGE,oldStorage);
        GL20.glUseProgram(oldProgram);
    }

    @Override public void close() {
        GL15.glDeleteBuffers(staging);
        GL20.glDeleteProgram(program);
    }
}
