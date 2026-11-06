package viewpoint.render;

import java.nio.*;
import java.util.*;
import org.json.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Packs the consumed original register layout from real host inputs.
 * No artistic fallbacks: an absent active camera/light/object field is rejected.
 * A caller revision updates frame data once per frame, not once per submesh.
 */
public final class AimohGf2RegisterBank implements AutoCloseable {
    public final int buffer,size;
    public final String sourceName;
    private final ByteBuffer bytes;
    private final JSONArray fields;
    private long uploaded=Long.MIN_VALUE;

    public AimohGf2RegisterBank(JSONObject layout){
        sourceName=layout.getString("name");size=(layout.getInt("size")+15)&~15;fields=layout.getJSONArray("fields");
        if(size<=0||size>GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE))throw new IllegalArgumentException("Invalid original register size");
        bytes=BufferUtils.createByteBuffer(size).order(ByteOrder.nativeOrder());buffer=GL15.glGenBuffers();
        int previous=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,buffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER,size,GL15.GL_DYNAMIC_DRAW);GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,previous);
    }
    public void update(Map<String,float[]> inputs,long revision){
        if(revision==uploaded)return;
        for(int i=0;i<fields.length();i++){
            JSONObject field=fields.getJSONObject(i);String name=field.getString("name");float[] value=inputs.get(name);
            if(value==null)throw new IllegalArgumentException("Missing consumed "+sourceName+" input "+name);
            int offset=field.getInt("m_Index"),rows=field.optInt("m_RowCount",0),array=field.optInt("m_ArraySize",0);
            int components=rows>0?Math.multiplyExact(Math.multiplyExact(rows,4),Math.max(array,1)):array>0?Math.multiplyExact(array,4):field.getInt("m_Dim");
            if(value.length!=components||offset<0||offset+components*4>size)throw new IllegalArgumentException("Original register field shape mismatch "+name);
            if(field.getInt("m_Type")!=0)throw new IllegalArgumentException("Original non-float register requires explicit typed input "+name);
            for(int k=0;k<components;k++){
                if(!Float.isFinite(value[k]))throw new IllegalArgumentException("Non-finite original register input "+name);
                bytes.putFloat(offset+k*4,value[k]);
            }
        }
        int previous=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,buffer);
        bytes.position(0);bytes.limit(size);GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER,0,bytes);GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,previous);uploaded=revision;
    }
    @Override public void close(){GL15.glDeleteBuffers(buffer);}
}
