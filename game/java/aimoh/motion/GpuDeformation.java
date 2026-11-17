package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/** Owns a model's immutable GPU inputs and two completed mesh buffers. GL work stays on the render thread. */
public final class GpuDeformation implements AutoCloseable {
    private static final int STORAGE=GL43.GL_SHADER_STORAGE_BUFFER;
    private static final String COMPUTE="""
        #version 430
        layout(local_size_x=128) in;
        struct Vertex { vec4 position; vec4 normal; ivec4 bone; vec4 weight; };
        struct Chest { vec4 positionAngle; vec4 normal; vec4 pivot; };
        struct Output { vec4 position; vec4 normal; };
        layout(std430,binding=0) readonly buffer V { Vertex rest[]; };
        layout(std430,binding=1) readonly buffer B { mat4 matrices[]; };
        layout(std430,binding=2) readonly buffer MS { uint morphSpans[]; };
        layout(std430,binding=3) readonly buffer M { vec4 morphs[]; };
        layout(std430,binding=4) readonly buffer W { float morphWeights[]; };
        layout(std430,binding=5) readonly buffer CS { uint chestSpans[]; };
        layout(std430,binding=6) readonly buffer C { Chest chest[]; };
        layout(std430,binding=7) writeonly buffer O { Output vertices[]; };
        uniform uint vertexCount;
        uniform bool chestActive;
        uniform vec3 swingAxis;
        vec3 unit(vec3 v) { float l=length(v); return l>1e-8?v/l:vec3(0); }
        vec3 turn(vec3 v,float angle) {
            float c=cos(angle),s=sin(angle);
            return c*v+s*cross(swingAxis,v)+(1-c)*dot(swingAxis,v)*swingAxis;
        }
        void main() {
            uint id=gl_GlobalInvocationID.x;
            if(id>=vertexCount)return;
            Vertex v=rest[id];vec3 p=v.position.xyz;
            for(uint i=morphSpans[id];i<morphSpans[id+1];i++) {
                vec4 row=morphs[i];p+=row.xyz*morphWeights[int(row.w)];
            }
            vec3 position=vec3(0),normal=vec3(0);
            for(int i=0;i<4;i++) {
                float w=v.weight[i];if(w==0)continue;
                mat4 skin=v.bone[i]<0?mat4(1):matrices[v.bone[i]];
                position+=w*(skin*vec4(p,1)).xyz;
                normal+=w*(skin*vec4(v.normal.xyz,0)).xyz;
            }
            normal=unit(normal);
            if(chestActive) {
                uint first=chestSpans[id],end=chestSpans[id+1];
                for(uint i=first;i<end;i++) {
                    Chest c=chest[i];position+=c.positionAngle.xyz;normal+=c.normal.xyz;
                    if(abs(c.positionAngle.w)>1e-5) {
                        position=c.pivot.xyz+turn(position-c.pivot.xyz,c.positionAngle.w);
                        normal=turn(normal,c.positionAngle.w);
                    }
                }
                if(first!=end)normal=unit(normal);
            }
            vertices[id].position=vec4(position,1);vertices[id].normal=vec4(normal,0);
        }
        """;

    private final NativeFunc api;
    private final PmxModel model;
    private final int vertices,morphCount,bones;
    private final ByteBuffer palette,weights;
    private final int[] input=new int[7],output=new int[2];
    private final Pose[] cached=new Pose[2];
    private final int[] savedBindings=new int[8];
    private int program,next,vertexCountUniform,chestActiveUniform,swingAxisUniform;
    private int[] uploadedChestSpans;
    private boolean closed;

    public static GpuDeformation create(NativeFunc api,PmxModel model) {
        if(!GL.getCapabilities().OpenGL43||GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)<8)return null;
        byte[] source=api.GetGpuDeformationSource(model.handle());
        return source==null?null:new GpuDeformation(api,model,source);
    }

    private GpuDeformation(NativeFunc api,PmxModel model,byte[] source) {
        this.api=api;this.model=model;
        ByteBuffer bytes=BufferUtils.createByteBuffer(source.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(source).flip();vertices=bytes.getInt();morphCount=bytes.getInt();int rows=bytes.getInt();
        bones=api.GetBoneCount(model.handle());
        if(vertices!=api.GetVertexCount(model.handle())||vertices<=0||morphCount<0||rows<0
                ||bytes.remaining()!=64L*vertices+4L*(vertices+1)+16L*rows)throw new IllegalArgumentException("GPU deformation source layout differs");
        palette=BufferUtils.createByteBuffer(Math.multiplyExact(bones,64));
        weights=BufferUtils.createByteBuffer(Math.multiplyExact(morphCount,4));
        int bound=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            program=link(COMPUTE);
            vertexCountUniform=GL20.glGetUniformLocation(program,"vertexCount");
            chestActiveUniform=GL20.glGetUniformLocation(program,"chestActive");
            swingAxisUniform=GL20.glGetUniformLocation(program,"swingAxis");
            for(int i=0;i<input.length;i++)input[i]=GL15.glGenBuffers();
            for(int i=0;i<output.length;i++){
                output[i]=GL15.glGenBuffers();GL15.glBindBuffer(STORAGE,output[i]);
                GL15.glBufferData(STORAGE,32L*vertices,GL15.GL_DYNAMIC_COPY);
            }
            upload(0,take(bytes,Math.multiplyExact(vertices,64)),GL15.GL_STATIC_DRAW);
            upload(2,take(bytes,Math.multiplyExact(vertices+1,4)),GL15.GL_STATIC_DRAW);
            upload(3,bytes,GL15.GL_STATIC_DRAW);
            for(int slot:new int[]{1,4,5,6}) {
                GL15.glBindBuffer(STORAGE,input[slot]);GL15.glBufferData(STORAGE,16,GL15.GL_STREAM_DRAW);
            }
        } catch(RuntimeException|Error failure) {
            close();throw failure;
        } finally { GL15.glBindBuffer(STORAGE,bound); }
    }

    private static ByteBuffer take(ByteBuffer bytes,int length) {
        ByteBuffer view=bytes.slice(bytes.position(),length);bytes.position(bytes.position()+length);return view;
    }

    static int link(String source) {
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);int linked=0;
        try {
            GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            linked=GL20.glCreateProgram();GL20.glAttachShader(linked,shader);GL20.glLinkProgram(linked);
            if(GL20.glGetProgrami(linked,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
            return linked;
        } catch(RuntimeException|Error failure) {
            if(linked!=0)GL20.glDeleteProgram(linked);throw failure;
        } finally {GL20.glDeleteShader(shader);}
    }

    private void upload(int slot,ByteBuffer data,int usage) {
        GL15.glBindBuffer(STORAGE,input[slot]);
        if(data.hasRemaining())GL15.glBufferData(STORAGE,data,usage);
        else GL15.glBufferData(STORAGE,16,usage);
    }

    public static final class Pose {
        private final GpuDeformation mesh;
        private final float[] bones,weights;
        private final ChestSoftBody.GpuOffsets chest;
        private Pose(GpuDeformation mesh,float[] bones,float[] weights,ChestSoftBody.GpuOffsets chest) {
            this.mesh=mesh;this.bones=bones;this.weights=weights;this.chest=chest;
        }
        public int vertexCount() { return mesh.vertices; }
        public int vertexBuffer() { return mesh.deform(this); }
    }

    public Pose capture() {
        long handle=model.handle();
        if(api.CopySkinningMatricesToBuffer(handle,palette)!=bones||api.CopyGpuDeformationWeights(handle,weights)!=morphCount)
            throw new IllegalStateException("Incomplete GPU pose");
        float[] matrices=new float[bones*16],values=new float[morphCount];
        palette.asFloatBuffer().get(matrices);weights.asFloatBuffer().get(values);
        return new Pose(this,matrices,values,model.gpuChestOffsets(vertices));
    }

    private int deform(Pose pose) {
        if(closed)throw new IllegalStateException("Retired GPU mesh");
        for(int i=0;i<cached.length;i++)if(cached[i]==pose)return output[i];
        int bound=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING),oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        for(int i=0;i<savedBindings.length;i++)savedBindings[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
        try {
            uploadFloats(1,pose.bones);uploadFloats(4,pose.weights);
            if(pose.chest!=null) {
                if(uploadedChestSpans!=pose.chest.spans()) {
                    ByteBuffer spans=BufferUtils.createByteBuffer(pose.chest.spans().length*4);
                    spans.asIntBuffer().put(pose.chest.spans());upload(5,spans,GL15.GL_STATIC_DRAW);
                    uploadedChestSpans=pose.chest.spans();
                }
                uploadFloats(6,pose.chest.rows());
            }
            for(int i=0;i<input.length;i++)GL30.glBindBufferBase(STORAGE,i,input[i]);
            GL30.glBindBufferBase(STORAGE,7,output[next]);
            GL20.glUseProgram(program);
            GL30.glUniform1ui(vertexCountUniform,vertices);
            GL20.glUniform1i(chestActiveUniform,pose.chest==null?0:1);
            GL20.glUniform3f(swingAxisUniform,pose.chest==null?0:pose.chest.axisX(),0,
                pose.chest==null?0:pose.chest.axisZ());
            GL43.glDispatchCompute((vertices+127)/128,1,1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
            cached[next]=pose;int result=output[next];next^=1;return result;
        } finally {
            for(int i=0;i<savedBindings.length;i++)GL30.glBindBufferBase(STORAGE,i,savedBindings[i]);
            GL15.glBindBuffer(STORAGE,bound);GL20.glUseProgram(oldProgram);
        }
    }

    private void uploadFloats(int slot,float[] values) {
        GL15.glBindBuffer(STORAGE,input[slot]);
        if(values.length==0)GL15.glBufferData(STORAGE,16,GL15.GL_STREAM_DRAW);
        else GL15.glBufferData(STORAGE,values,GL15.GL_STREAM_DRAW);
    }

    @Override public void close() {
        if(closed)return;closed=true;
        for(int id:input)if(id!=0)GL15.glDeleteBuffers(id);
        for(int id:output)if(id!=0)GL15.glDeleteBuffers(id);
        if(program!=0)GL20.glDeleteProgram(program);
    }
}
