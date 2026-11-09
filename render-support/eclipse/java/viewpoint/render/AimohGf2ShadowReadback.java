package viewpoint.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

/** Copy the current shadow matrix ahead of the opaque work that does not use it. */
final class AimohGf2ShadowReadback implements AutoCloseable {
    private static final int MATRIX_BYTES=16*Float.BYTES;
    private static final int FLAGS=GL30.GL_MAP_READ_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private int buffer;
    private ByteBuffer mapped;
    private long fence;
    private boolean unavailable;

    void queue(int source,int offset){
        if(unavailable)return;
        if(mapped==null){
            var caps=GL.getCapabilities();
            if(!caps.OpenGL44&&!caps.GL_ARB_buffer_storage){unavailable=true;return;}
            buffer=GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(buffer,(long)MATRIX_BYTES,FLAGS);
            mapped=GL45.glMapNamedBufferRange(buffer,0,MATRIX_BYTES,FLAGS);
            if(mapped==null){
                GL45.glDeleteBuffers(buffer);buffer=0;unavailable=true;
                return;
            }
            mapped.order(ByteOrder.nativeOrder());
        }
        GL45.glCopyNamedBufferSubData(source,buffer,offset,0,MATRIX_BYTES);
        fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
    }

    float[] read(){
        if(fence==0)return null;
        // Coherent mapped reads see the writes preceding this fence. Later scene draws can stay queued.
        int status=GL32.glClientWaitSync(fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);
        reset();
        if(status!=GL32.GL_ALREADY_SIGNALED&&status!=GL32.GL_CONDITION_SATISFIED)return null;
        float[] values=new float[16];mapped.asFloatBuffer().get(values);
        return values;
    }

    void reset(){
        if(fence!=0){GL32.glDeleteSync(fence);fence=0;}
    }

    @Override public void close(){
        reset();
        if(mapped!=null){GL45.glUnmapNamedBuffer(buffer);mapped=null;}
        if(buffer!=0){GL45.glDeleteBuffers(buffer);buffer=0;}
    }
}
