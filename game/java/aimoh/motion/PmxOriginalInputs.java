package aimoh.motion;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** Optional pack capability. No rig, pose, physics or filesystem model edits. */
public final class PmxOriginalInputs {
    public record Draw(int material,int sourceMaterial,int[] source,int[] attributes,int[] indices,boolean sourceAlphaIndependent){}
    public final List<Draw> draws;public final String key;
    private PmxOriginalInputs(String key,List<Draw> draws){this.key=key;this.draws=List.copyOf(draws);}
    private static Class<?> host(){try{return Class.forName("viewpoint.render.Gf2SourceHost",true,PmxOriginalInputs.class.getClassLoader());}catch(ClassNotFoundException absent){return null;}}
    private static Object invoke(Class<?> host,String name,Class<?>[] types,Object...args)throws ReflectiveOperationException {
        try{return host.getMethod(name,types).invoke(null,args);}catch(InvocationTargetException e){if(e.getCause()instanceof RuntimeException error)throw error;throw e;}
    }
    public static String key(PmxShading shading){
        if(shading==null||!shading.danrp)return null;Class<?> host=host();if(host==null)return null;
        try{return(String)invoke(host,"profileKey",new Class<?>[]{int.class,int.class},(int)shading.modelToken0,(int)shading.modelToken1);}catch(ReflectiveOperationException e){throw new IllegalStateException("Source profile capability failed",e);}
    }
    public static PmxOriginalInputs load(PmxShading shading,int vertexCount,int materialCount){
        String key=key(shading);if(key==null)return null;
        try{
            Class<?> host=host();String description=(String)invoke(host,"modelDescriptor",new Class<?>[]{String.class,int.class,int.class,int.class,int.class},key,(int)shading.modelToken0,(int)shading.modelToken1,vertexCount,materialCount);
            JSONObject packet=new JSONObject(description),selected=packet.getJSONObject("model");JSONArray shaders=packet.getJSONArray("material_shaders");
            if(selected.getInt("format")!=2||selected.getInt("original_pmx_vertices")!=vertexCount||selected.getInt("original_pmx_materials")!=materialCount)throw new IllegalArgumentException("Source PMX topology differs");
            int records=selected.getInt("attribute_records");if(records<=0||records>262144)throw new IllegalArgumentException("Source attribute carrier range");
            JSONObject attributes=selected.getJSONObject("attributes"),geometry=selected.getJSONObject("geometry");
            byte[] attributeBytes=(byte[])invoke(host,"resourceBytes",new Class<?>[]{String.class,String.class},key,attributes.getString("path"));
            if(attributeBytes.length!=(long)selected.getInt("texture_width")*selected.getInt("texture_height")*16)throw new IllegalArgumentException("Source attribute image shape differs");
            byte[] raw=(byte[])invoke(host,"resourceBytes",new Class<?>[]{String.class,String.class},key,geometry.getString("path"));ByteBuffer data=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);List<Draw> draws=new ArrayList<>();
            for(Object value:selected.getJSONArray("parts")){
                JSONObject part=(JSONObject)value;int material=part.getInt("pmx_material"),sourceMaterial=part.getInt("source_material"),count=part.getInt("vertices"),length=part.getInt("indices"),vo=part.getInt("vertex_offset"),io=part.getInt("index_offset");
                if(material<0||material>=materialCount||sourceMaterial<0||sourceMaterial>=shaders.length()||count<0||length<0||length%3!=0||vo<0||io<0||vo%4!=0||io%4!=0||(long)vo+count*8L>raw.length||(long)io+length*4L>raw.length)throw new IllegalArgumentException("Invalid source draw descriptor");
                int[] source=new int[count],carrier=new int[count],indices=new int[length];
                for(int i=0;i<count;i++){source[i]=data.getInt(vo+i*8);carrier[i]=data.getInt(vo+i*8+4);if(source[i]<0||source[i]>=vertexCount||carrier[i]<0||carrier[i]>=records)throw new IllegalArgumentException("Source vertex outside range");}
                for(int i=0;i<length;i++){indices[i]=data.getInt(io+i*4);if(indices[i]<0||indices[i]>=count)throw new IllegalArgumentException("Source triangle outside compact range");}
                String shader=shaders.getString(sourceMaterial);draws.add(new Draw(material,sourceMaterial,source,carrier,indices,shader.endsWith("/eyeblend_add")||shader.endsWith("/eyeblend_multiply")));
            }
            return new PmxOriginalInputs(key,draws);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Pack-owned source PMX inputs failed",e);}
    }
}
