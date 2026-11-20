package viewpoint.render;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.json.*;
import viewpoint.platform.*;

/** One Native renderer per actual drawn pack generation. Data preparation has no GL calls. */
public final class Gfl2NativeRuntime {
    public static int somberCoverageTexture(long frame) {
        return frameRenderer == null ? 0 : frameRenderer.somberCoverageTexture(frame);
    }
    private static ShaderPacks.Active selected,rendererPack;
    private static Gfl2PackData prepared,drawnData;
    private static Gfl2NativeSourceRenderer renderer,frameRenderer;
    private static boolean diagnostics;
    private static boolean irisSelected;
    private record CatalogRequest(String bootstrap,Gfl2PackData.CatalogInputs inputs){}
    private static CatalogRequest request;
    private static Future<Gfl2PackData> catalog;
    private static final ExecutorService catalogLoader=Executors.newSingleThreadExecutor(work->{
        Thread thread=new Thread(work,"GFL2-render-catalog");thread.setDaemon(true);return thread;
    });
    private static int created,released;
    public static synchronized void prepareData(){
        ShaderPacks.Active drawn=ShaderPacks.drawn();
        boolean iris=IrisPacks.active()!=null;
        if(selected!=drawn||irisSelected!=iris||request!=null&&!request.inputs.current())select(drawn,iris);
        if(prepared!=null||catalog==null||!catalog.isDone())return;
        // A completed worker may only publish for the currently mounted MOD directories.
        if(!request.inputs.equals(Gfl2PackData.CatalogInputs.capture())){
            select(drawn,iris);
            return;
        }
        try{
            prepared=catalog.get();
            Gfl2NativeRegistry.attach(prepared,drawn);
        }catch(ExecutionException failed){
            catalog=null;
            reportLoadFailure(failed.getCause());
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
        }
    }
    private static void select(ShaderPacks.Active drawn,boolean iris){
        if(prepared!=null)Gfl2NativeRegistry.detach(prepared);
        selected=drawn;irisSelected=iris;prepared=null;
        CatalogRequest next=null;
        try{next=catalogRequest(drawn,iris);}
        catch(IOException|IllegalArgumentException|JSONException e){
            reportLoadFailure(e);
        }
        if(catalog!=null&&Objects.equals(request,next))return;
        cancelCatalog();request=next;
        if(next!=null){
            CatalogRequest input=next;
            catalog=catalogLoader.submit(()->new Gfl2PackData(input.bootstrap,input.inputs));
        }
    }
    private static CatalogRequest catalogRequest(ShaderPacks.Active drawn,boolean iris)throws IOException{
        if(iris||drawn==null)return null;
        var files=Gfl2NativePackSelection.files(drawn);
        String text=files.read("gf2/manifest.json");if(text==null)return null;
        JSONObject bootstrap=new JSONObject(text);JSONObject addon=bootstrap.optJSONObject("addon_runtime");
        if(addon==null||!"GFL2Exilium".equals(addon.optString("id"))||addon.optInt("format")!=1||!"native".equals(addon.optString("backend")))return null;
        return new CatalogRequest(text,Gfl2PackData.CatalogInputs.capture());
    }
    private static void cancelCatalog(){
        if(catalog!=null)catalog.cancel(false);
        catalog=null;request=null;
    }
    private static void reportLoadFailure(Throwable error){
        if(error instanceof Error fatal)throw fatal;
        System.err.println("[GFL2 Native] Character rendering unavailable; check the GFL2 character MOD and restart or reload the shader: "+error);
    }
    public static synchronized void prepareFrame(){
        frameRenderer=null;
        Gfl2NativeRegistry.frame(null);
        prepareData();
        if(renderer==null&&prepared==null||renderer!=null&&drawnData==prepared&&rendererPack==selected&&Gfl2NativeRegistry.active())return;
        if(prepared!=null){
            String problem=Gfl2Device.unsupported();
            if(problem!=null){
                System.err.println("[GFL2 Native] Character rendering unavailable: "+problem);
                Gfl2NativeRegistry.detach(prepared);prepared=null;catalog=null;
                if(renderer==null)return;
            }
        }
        try(Gfl2NativeSourceScope scope=new Gfl2NativeSourceScope(Gfl2GlScope.Phase.SETUP)){
            if(renderer!=null&&(drawnData!=prepared||rendererPack!=selected||!Gfl2NativeRegistry.active())){renderer.close();renderer=null;rendererPack=null;drawnData=null;released++;}
            if(prepared!=null&&renderer==null){
                try{
                    renderer=new Gfl2NativeSourceRenderer(prepared);drawnData=prepared;rendererPack=selected;created++;if(diagnostics)renderer.diagnostics();
                }catch(IOException|IllegalArgumentException|JSONException e){
                    System.err.println("[GFL2 Native] Character resources unavailable: "+e);
                    Gfl2NativeRegistry.detach(prepared);prepared=null;catalog=null;
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
    public static synchronized String lifecycle(){return new JSONObject().put("created",created).put("released",released).put("renderer_active",renderer!=null).put("data_active",Gfl2NativeRegistry.active()).put("catalog_loading",prepared==null&&catalog!=null).put("drawn_generation",selected==null?-1:selected.generation).toString();}
    public static synchronized void close(){
        frameRenderer=null;Gfl2NativeRegistry.frame(null);
        try{if(renderer!=null){renderer.close();released++;}}catch(Exception e){throw new IllegalStateException("Native GF2 release",e);}finally{renderer=null;rendererPack=null;drawnData=null;if(prepared!=null)Gfl2NativeRegistry.detach(prepared);prepared=null;selected=null;cancelCatalog();}
    }
    private Gfl2NativeRuntime(){}
}
