package viewpoint.render;
import java.io.IOException;
import org.json.*;
import viewpoint.platform.*;

/** Native source-data capability; pose/physics and artistic material values are not owned here. */
public final class AimohNativeGf2Registry {
    private record State(AimohGf2PackData data,String pack,int generation,String key,JSONArray shaders){}
    private static volatile State current;
    private static long epoch;
    // Render-thread ownership is fixed when the host enters its Native world path.
    private static AimohGf2PackData frameData;
    public static viewpoint.iris.PackSource packedSource(viewpoint.iris.SourceFiles source){return source instanceof viewpoint.iris.PackSource packed?packed:null;}
    public static byte[] packedBytes(viewpoint.iris.PackSource source,String path)throws IOException{if(source==null)throw new IOException("Native character resources must be external in the MOD: "+path);return source.readBytes(path);}
    static synchronized void attach(AimohGf2PackData data,ShaderPacks.Active drawn){
        JSONArray shaders=new JSONArray();
        if(current!=null&&current.data==data&&current.pack.equals(drawn.pack.id)&&current.generation==drawn.generation)return;
        for(Object value:data.manifest.getJSONArray("materials"))shaders.put(((JSONObject)value).getString("shader"));
        current=new State(data,drawn.pack.id,drawn.generation,"native:"+data.identity+":"+(++epoch),shaders);
    }
    public static synchronized void detach(){current=null;}
    public static synchronized void detach(AimohGf2PackData data){if(current!=null&&current.data==data)detach();}
    static void frame(AimohGf2PackData data){frameData=data;}
    public static boolean active(){
        return active(current);
    }
    private static boolean active(State state){
        if(state==null||IrisPacks.active()!=null)return false;
        var drawn=ShaderPacks.drawn();return drawn!=null&&state.pack.equals(drawn.pack.id)&&state.generation==drawn.generation;
    }
    public static String profileKey(int a,int b){
        State s=current;return active(s)&&s.data.model(a,b)!=null?s.key:null;
    }
    public static boolean owns(ModelDraws draws,int index){
        AimohGf2PackData data=frameData;
        if(data==null||index<0||index>=draws.count)return false;
        var mesh=draws.meshes[index];
        return mesh!=null&&mesh.getPath()!=null&&mesh.getPath().getPath().startsWith("aimoh:pmx-part-gf2-")
            &&data.modelForPalette(draws,draws.palette[index])!=null;
    }
    private static State require(String key){
        State s=current;if(!active(s)||!s.key.equals(key))throw new IllegalStateException("Native source profile changed during resource load");return s;
    }
    public static String modelDescriptor(String key,int a,int b,int vertices,int materials){
        State s=require(key);JSONObject model=s.data.model(a,b,vertices,materials);
        if(model==null)return null;
        return new JSONObject().put("model",model).put("material_shaders",s.shaders).toString();
    }
    public static byte[] resourceBytes(String key,String path)throws IOException{return require(key).data.bytes(path);}
    private AimohNativeGf2Registry(){}
}
