package viewpoint.render;
import java.io.IOException;
import org.json.*;
import viewpoint.platform.*;
import zombie.ZomboidFileSystem;

/** One Native renderer per actual drawn pack generation. Data preparation has no GL calls. */
public final class AimohNativeGf2Runtime {
    private static ShaderPacks.Active selected;
    private static AimohGf2PackData prepared,drawnData;
    private static AimohNativeSourceRenderer renderer;
    private static boolean diagnostics;
    private static boolean irisSelected;
    private static String rejectedCatalog;
    private static int created,released;
    public static synchronized void prepareData(){
        ShaderPacks.Active drawn=ShaderPacks.drawn();
        boolean iris=IrisPacks.active()!=null;
        if(selected==drawn&&irisSelected==iris&&(rejectedCatalog==null||rejectedCatalog.equals(catalogLocation())))return;
        if(prepared!=null)AimohNativeGf2Registry.detach(prepared);
        selected=drawn;irisSelected=iris;prepared=null;rejectedCatalog=null;
        try{prepared=loadData(drawn,iris);}
        catch(IOException|IllegalArgumentException|JSONException e){
            rejectedCatalog=catalogLocation();
            System.err.println("[R116 Native] Character rendering unavailable; check the R116 character MOD and restart or reload the shader: "+e);
        }
        if(prepared!=null)AimohNativeGf2Registry.attach(prepared);
    }
    private static String catalogLocation(){return ZomboidFileSystem.instance.getString(AimohGf2PackData.CATALOG_PATH);}
    private static AimohGf2PackData loadData(ShaderPacks.Active drawn,boolean iris)throws IOException{
        if(iris||drawn==null)return null;
        var files=AimohNativePackSelection.files(drawn);
        String text=files.read("gf2/manifest.json");if(text==null)return null;
        JSONObject bootstrap=new JSONObject(text);JSONObject addon=bootstrap.optJSONObject("addon_runtime");
        if(addon==null||!"AimohGF2Eclipse".equals(addon.optString("id"))||addon.optInt("format")!=1||!"native".equals(addon.optString("backend")))return null;
        return new AimohGf2PackData(files);
    }
    public static void prepareFrame(){
        prepareData();
        if(renderer==null&&prepared==null||renderer!=null&&drawnData==prepared&&AimohNativeGf2Registry.active())return;
        if(prepared!=null){
            String problem=AimohGf2Device.unsupported();
            if(problem!=null){
                System.err.println("[R116 Native] Character rendering unavailable: "+problem);
                AimohNativeGf2Registry.detach(prepared);prepared=null;
                if(renderer==null)return;
            }
        }
        try(AimohNativeSourceScope scope=new AimohNativeSourceScope()){
            if(renderer!=null&&(drawnData!=prepared||!AimohNativeGf2Registry.active())){renderer.close();renderer=null;drawnData=null;released++;}
            if(prepared!=null&&renderer==null){
                try{
                    renderer=new AimohNativeSourceRenderer(prepared);drawnData=prepared;created++;if(diagnostics)renderer.diagnostics();
                }catch(IOException|IllegalArgumentException|JSONException e){
                    System.err.println("[R116 Native] Character resources unavailable: "+e);
                    AimohNativeGf2Registry.detach(prepared);prepared=null;
                }
            }
        }catch(Exception e){throw new IllegalStateException("Native GF2 renderer preparation",e);}
    }
    public static void draw(FrameContext frame){
        if(renderer==null)return;
        int[] before=diagnostics?AimohNativeSourceScope.bindings():null;
        try{renderer.draw(frame);}catch(Exception e){throw new IllegalStateException("Native GF2 AFTER_OPAQUE",e);}
        if(diagnostics&&!java.util.Arrays.equals(before,AimohNativeSourceScope.bindings()))throw new IllegalStateException("Native GF2 shared GL bindings leaked");
    }
    public static void diagnostics(){diagnostics=true;if(renderer!=null)renderer.diagnostics();}
    public static String evidence(){return renderer==null?null:renderer.evidence();}
    public static String lifecycle(){return new JSONObject().put("created",created).put("released",released).put("renderer_active",renderer!=null).put("data_active",AimohNativeGf2Registry.active()).put("drawn_generation",selected==null?-1:selected.generation).toString();}
    public static void close(){
        try{if(renderer!=null){renderer.close();released++;}}catch(Exception e){throw new IllegalStateException("Native GF2 release",e);}finally{renderer=null;drawnData=null;if(prepared!=null)AimohNativeGf2Registry.detach(prepared);prepared=null;selected=null;rejectedCatalog=null;}
    }
    private AimohNativeGf2Runtime(){}
}
