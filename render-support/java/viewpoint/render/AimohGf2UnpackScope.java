package viewpoint.render;
import org.lwjgl.opengl.*;

/** CPU texture uploads own their unpack layout, regardless of the host's last upload. */
final class AimohGf2UnpackScope implements AutoCloseable {
    private static final int[] KEYS={GL11.GL_UNPACK_ALIGNMENT,GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_PIXELS,GL11.GL_UNPACK_SKIP_ROWS,GL12.GL_UNPACK_IMAGE_HEIGHT,GL12.GL_UNPACK_SKIP_IMAGES,GL11.GL_UNPACK_SWAP_BYTES,GL11.GL_UNPACK_LSB_FIRST,GL42.GL_UNPACK_COMPRESSED_BLOCK_WIDTH,GL42.GL_UNPACK_COMPRESSED_BLOCK_HEIGHT,GL42.GL_UNPACK_COMPRESSED_BLOCK_DEPTH,GL42.GL_UNPACK_COMPRESSED_BLOCK_SIZE};
    private final int buffer=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int[] values=new int[KEYS.length];
    AimohGf2UnpackScope(){for(int i=0;i<KEYS.length;i++){values[i]=GL11.glGetInteger(KEYS[i]);GL11.glPixelStorei(KEYS[i],i==0?1:0);}GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);}
    public void close(){for(int i=0;i<KEYS.length;i++)GL11.glPixelStorei(KEYS[i],values[i]);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,buffer);}
}
