package viewpoint.render;
import java.io.IOException;
import org.json.*;
import viewpoint.platform.*;

/** Native source-data capability; pose/physics and artistic material values are not owned here. */
public final class AimohNativeGf2Registry {
    private record State(AimohGf2PackData data,String pack,int generation,String key,JSONArray shaders){}
    private static volatile State current;
    private static long epoch;
    private static java.util.function.Consumer<FrameContext> prepass;
    public interface ShadowDraw{void draw(org.joml.Matrix4f matrix,int[] candidates,int count,ModelBatches.Filter filter);}
    private static ShadowDraw shadowDraw;
    public static viewpoint.iris.PackSource packedSource(viewpoint.iris.SourceFiles source){return source instanceof viewpoint.iris.PackSource packed?packed:null;}
    public static byte[] packedBytes(viewpoint.iris.PackSource source,String path)throws IOException{if(source==null)throw new IOException("Native character resources must be external in the MOD: "+path);return source.readBytes(path);}
    public static synchronized void attach(AimohGf2PackData data){
        if(data==null||IrisPacks.active()!=null)throw new IllegalStateException("Native data must attach to a real Native renderer");
        var drawn=ShaderPacks.drawn();JSONArray shaders=new JSONArray();
        if(current!=null&&current.data==data&&current.pack.equals(drawn.pack.id)&&current.generation==drawn.generation)return;
        for(Object value:data.manifest.getJSONArray("materials"))shaders.put(((JSONObject)value).getString("shader"));
        prepass=null;shadowDraw=null;
        current=new State(data,drawn.pack.id,drawn.generation,"native:"+data.identity+":"+(++epoch),shaders);
    }
    public static synchronized void detach(){current=null;prepass=null;shadowDraw=null;}
    public static synchronized void detach(AimohGf2PackData data){if(current!=null&&current.data==data)detach();}
    public static void beforeLighting(java.util.function.Consumer<FrameContext> draw){prepass=draw;}
    public static void beforeLighting(FrameContext frame){if(active()&&prepass!=null)prepass.accept(frame);}
    public static void shadows(ShadowDraw draw){shadowDraw=draw;}
    public static void shadows(org.joml.Matrix4f matrix,int[] candidates,int count,ModelBatches.Filter filter){if(active()&&shadowDraw!=null)shadowDraw.draw(matrix,candidates,count,filter);}
    public static boolean active(){
        return active(current);
    }
    private static boolean active(State state){
        if(state==null||IrisPacks.active()!=null)return false;
        var drawn=ShaderPacks.drawn();return drawn!=null&&state.pack.equals(drawn.pack.id)&&state.generation==drawn.generation;
    }
    public static String profileKey(int a,int b){
        State s=current;return active()&&s.data.model(a,b)!=null?s.key:null;
    }
    public static boolean owns(ModelDraws draws,int index){
        State state=current;
        if(!active(state)||prepass==null||shadowDraw==null||index<0||index>=draws.count)return false;
        var mesh=draws.meshes[index];
        return mesh!=null&&mesh.getPath()!=null&&mesh.getPath().getPath().startsWith("aimoh:pmx-part-gf2-")
            &&state.data.modelForPalette(draws,draws.palette[index])!=null;
    }
    private static State require(String key){
        State s=current;if(!active()||s==null||!s.key.equals(key))throw new IllegalStateException("Native source profile changed during resource load");return s;
    }
    public static String modelDescriptor(String key,int a,int b,int vertices,int materials){
        State s=require(key);JSONObject model=s.data.model(a,b,vertices,materials);
        if(model==null)return null;
        return new JSONObject().put("model",model).put("material_shaders",s.shaders).toString();
    }
    public static byte[] resourceBytes(String key,String path)throws IOException{return require(key).data.bytes(path);}
    private AimohNativeGf2Registry(){}
}
