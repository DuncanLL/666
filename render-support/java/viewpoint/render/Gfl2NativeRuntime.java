package viewpoint.render;
import java.io.IOException;
import org.json.*;
import viewpoint.platform.*;
import zombie.ZomboidFileSystem;

/** One Native renderer per actual drawn pack generation. Data preparation has no GL calls. */
public final class Gfl2NativeRuntime {
    public static int somberCoverageTexture(long frame) {
        return frameRenderer == null ? 0 : frameRenderer.somberCoverageTexture(frame);
    }
    private static ShaderPacks.Active selected;
    private static Gfl2PackData prepared,drawnData;
    private static Gfl2NativeSourceRenderer renderer,frameRenderer;
    private static boolean diagnostics;
    private static boolean irisSelected;
    private static String rejectedCatalog;
    private static int created,released;
    public static synchronized void prepareData(){
        ShaderPacks.Active drawn=ShaderPacks.drawn();
        boolean iris=IrisPacks.active()!=null;
        if(selected==drawn&&irisSelected==iris&&(rejectedCatalog==null||rejectedCatalog.equals(catalogLocation())))return;
        if(prepared!=null)Gfl2NativeRegistry.detach(prepared);
        selected=drawn;irisSelected=iris;prepared=null;rejectedCatalog=null;
        try{prepared=loadData(drawn,iris);}
        catch(IOException|IllegalArgumentException|JSONException e){
            rejectedCatalog=catalogLocation();
            System.err.println("[GFL2 Native] Character rendering unavailable; check the GFL2 character MOD and restart or reload the shader: "+e);
        }
        if(prepared!=null)Gfl2NativeRegistry.attach(prepared,drawn);
    }
    private static String catalogLocation(){return ZomboidFileSystem.instance.getString(Gfl2PackData.CATALOG_PATH);}
    private static Gfl2PackData loadData(ShaderPacks.Active drawn,boolean iris)throws IOException{
        if(iris||drawn==null)return null;
        var files=Gfl2NativePackSelection.files(drawn);
        String text=files.read("gf2/manifest.json");if(text==null)return null;
        JSONObject bootstrap=new JSONObject(text);JSONObject addon=bootstrap.optJSONObject("addon_runtime");
        if(addon==null||!"GFL2Exilium".equals(addon.optString("id"))||addon.optInt("format")!=1||!"native".equals(addon.optString("backend")))return null;
        return new Gfl2PackData(files);
    }
    public static synchronized void prepareFrame(){
        frameRenderer=null;
        Gfl2NativeRegistry.frame(null);
        prepareData();
        if(renderer==null&&prepared==null||renderer!=null&&drawnData==prepared&&Gfl2NativeRegistry.active())return;
        if(prepared!=null){
            String problem=Gfl2Device.unsupported();
            if(problem!=null){
                System.err.println("[GFL2 Native] Character rendering unavailable: "+problem);
                Gfl2NativeRegistry.detach(prepared);prepared=null;
                if(renderer==null)return;
            }
        }
        try(Gfl2NativeSourceScope scope=new Gfl2NativeSourceScope(Gfl2GlScope.Phase.SETUP)){
            if(renderer!=null&&(drawnData!=prepared||!Gfl2NativeRegistry.active())){renderer.close();renderer=null;drawnData=null;released++;}
            if(prepared!=null&&renderer==null){
                try{
                    renderer=new Gfl2NativeSourceRenderer(prepared);drawnData=prepared;created++;if(diagnostics)renderer.diagnostics();
                }catch(IOException|IllegalArgumentException|JSONException e){
                    System.err.println("[GFL2 Native] Character resources unavailable: "+e);
                    Gfl2NativeRegistry.detach(prepared);prepared=null;
                }
            }
        }catch(Exception e){throw new IllegalStateException("Native GF2 renderer preparation",e);}
    }
    public static void beginNativeFrame(){
        frameRenderer=renderer;
        Gfl2NativeRegistry.frame(frameRenderer==null?null:drawnData);
    }
    public static void beforeLighting(FrameContext frame){
        if(frameRenderer==null)return;
        try{frameRenderer.prepass(frame);}catch(Exception e){throw new IllegalStateException("Native source depth/GBuffer",e);}
    }
    public static void shadows(org.joml.Matrix4f matrix,int[] candidates,int count,ModelBatches.Filter filter){
        if(frameRenderer==null)return;
        try{frameRenderer.cast(matrix,candidates,count,filter);}catch(Exception e){throw new IllegalStateException("Native original ShadowCaster",e);}
    }
    public static void draw(FrameContext frame){
        if(frameRenderer==null)return;
        int[] before=diagnostics?Gfl2NativeSourceScope.bindings():null;
        try{frameRenderer.draw(frame);}catch(Exception e){throw new IllegalStateException("Native GF2 AFTER_OPAQUE",e);}
        if(diagnostics&&!java.util.Arrays.equals(before,Gfl2NativeSourceScope.bindings()))throw new IllegalStateException("Native GF2 shared GL bindings leaked");
    }
    public static void diagnostics(){diagnostics=true;if(renderer!=null)renderer.diagnostics();}
    public static String evidence(){return renderer==null?null:renderer.evidence();}
    public static String lifecycle(){return new JSONObject().put("created",created).put("released",released).put("renderer_active",renderer!=null).put("data_active",Gfl2NativeRegistry.active()).put("drawn_generation",selected==null?-1:selected.generation).toString();}
    public static synchronized void close(){
        frameRenderer=null;Gfl2NativeRegistry.frame(null);
        try{if(renderer!=null){renderer.close();released++;}}catch(Exception e){throw new IllegalStateException("Native GF2 release",e);}finally{renderer=null;drawnData=null;if(prepared!=null)Gfl2NativeRegistry.detach(prepared);prepared=null;selected=null;rejectedCatalog=null;}
    }
    private Gfl2NativeRuntime(){}
}
