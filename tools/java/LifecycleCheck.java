package aimoh.pmxpreview;

import aimoh.motion.*;
import com.shiroha.mmdskin.NativeFunc;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import zombie.core.opengl.RenderThread;
import viewpoint.render.Retirement;

/** Real PMX/GPU/retirement; inject failures only at the boundary under test. */
public final class LifecycleCheck {
    static NativeFunc api;static OzzGrip grip;static Path assets;static long retiredFrame;
    static JSONArray rows;static int checks;
    static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void drain()throws Exception{
        Method flush=RenderThread.class.getDeclaredMethod("flushInvokeQueue");flush.setAccessible(true);flush.invoke(null);
        Retirement.drawn(retiredFrame+=10000);GL11.glFinish();Retirement.collect();
    }
    static ModelSession create(int row)throws Exception{
        JSONObject r=rows.getJSONObject(row);
        return new ModelSession(api,grip,new Main.Entry(r.getString("id"),r.getString("label"),assets.resolve(r.getString("model")),assets.resolve(r.getString("profile"))));
    }
    static PmxDrawMesh.FrameData frame(ModelSession s,boolean badTexture)throws Exception{
        s.model.apply(s.reference,null,0);String[] paths=s.model.appearance().readTextures();
        for(int i=0;i<paths.length;i++)if(!paths[i].isBlank())paths[i]=s.entry.model().getParent().resolve(paths[i]).toString();
        if(badTexture)paths[0]=assets.resolve("deliberately-absent-texture.png").toString();
        s.materials=new ModelSession.Materials(1,0,s.model.appearance().readTextureFactors(),s.model.appearance().readMaterials(false),paths,s.model.appearance().readBothFaces());
        int count=s.positions.capacity()/4;float[] p=new float[count],n=new float[count];
        api.CopyDataToByteBuffer(s.positions,api.GetPoss(s.model.handle()),s.positions.capacity());
        api.CopyDataToByteBuffer(s.normals,api.GetNormals(s.model.handle()),s.normals.capacity());
        s.positions.asFloatBuffer().get(p);s.normals.asFloatBuffer().get(n);
        return new PmxDrawMesh.FrameData(p,n,s.model.appearance().readMaterials());
    }
    static void stage(ModelSession s)throws Exception{
        Method m=Main.class.getDeclaredMethod("stage",zombie.characters.IsoGameCharacter.class,ModelSession.class);m.setAccessible(true);m.invoke(null,null,s);
    }
    static void settings(ModelSession s){
        String previous=s.model.appearance().stateJson();JSONObject oldChest=s.model.chestSettings();
        long revision=s.model.appearance().revision();boolean physics=s.model.physicsEnabled();
        JSONObject changed=new JSONObject(previous);changed.getJSONArray("material_visibility").put(0,false);
        JSONObject input=new JSONObject().put("appearance",changed).put("appearance_controls",s.profile.getJSONObject("appearance_controls"))
            .put("chest_physics",new JSONObject().put("strength",101));
        boolean failed=false;try{s.applySettings(input,false);}catch(IllegalArgumentException e){failed=true;}
        check(failed,"Invalid late chest setting accepted");
        check(new JSONObject(previous).similar(new JSONObject(s.model.appearance().stateJson()))&&s.model.appearance().revision()==revision,"Invalid late field changed appearance");
        check(oldChest.similar(s.model.chestSettings())&&physics==s.model.physicsEnabled(),"Invalid field changed physics");
        input.put("chest_physics",oldChest).put("material_presets",4);
        failed=false;try{s.applySettings(input,false);}catch(JSONException e){failed=true;}
        check(failed&&new JSONObject(previous).similar(new JSONObject(s.model.appearance().stateJson())),"Invalid session metadata committed model settings");
        input.remove("material_presets");s.applySettings(input,false);
        check(!new JSONObject(s.model.appearance().stateJson()).getJSONArray("material_visibility").getBoolean(0),"Valid settings failed to apply");
        s.applySettings(new JSONObject().put("appearance",new JSONObject(previous)).put("appearance_controls",s.profile.getJSONObject("appearance_controls")).put("chest_physics",oldChest),false);
        check(new JSONObject(previous).similar(new JSONObject(s.model.appearance().stateJson()))&&oldChest.similar(s.model.chestSettings()),"Valid restore differs");
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=Path.of(args[1]);assets=root.resolve("media/aimoh_pmx");
        zombie.ZomboidFileSystem.instance.setCacheDir(out.resolve("User").toString());zombie.ZomboidFileSystem.instance.init();
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(32,32,"R116 lifecycle checks",0,0);if(window==0)throw new AssertionError("Hidden GL context");
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            zombie.core.skinnedmodel.model.VertexBufferObject.funcs=new zombie.core.VBO.GLBufferObject15();
            field(RenderThread.class,"isInitialized").setBoolean(null,true);field(RenderThread.class,"contextThread").set(null,Thread.currentThread());
            field(Main.class,"directory").set(null,root);Method init=Main.class.getDeclaredMethod("nativeRuntime");init.setAccessible(true);init.invoke(null);
            api=(NativeFunc)field(Main.class,"api").get(null);grip=(OzzGrip)field(Main.class,"grip").get(null);
            rows=new JSONObject(Files.readString(assets.resolve("registry.json"))).getJSONArray("models");
            int softRow=-1;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals("pmx-c7270af966e8aaf8"))softRow=i;
            check(softRow>=0,"Chest model missing");
            ModelSession original=create(0);stage(original);
            check(Main.session(null)==null&&!Main.isReady(null),"Initial pending model became active");
            check(Main.completeCapture(null,original)==null,"Unprepared initial candidate committed");
            original.prepareMesh(frame(original,false),true);check(original.ready,"Initial GPU prepare");
            check(Main.completeCapture(null,original)==original&&Main.isReady(null),"Initial commit");
            String originalId=Main.modelId(null);
            ModelSession bad=create(softRow);stage(bad);settings(bad);bad.prepareMesh(frame(bad,true),true);
            check(bad.failed,"Real missing texture did not fail");
            check(Main.completeCapture(null,bad)==original&&!original.retired&&Main.modelId(null).equals(originalId),"Candidate failure replaced old actor");
            check(Main.hasModelFailure(null)&&bad.retired&&Main.isReady(null),"Failure not reported or old model not ready");drain();
            check(ChestSoftBody.liveWorlds()==0,"Failed candidate chest leaked");
            for(int i=0;i<4;i++){
                ModelSession cancelled=create(softRow);stage(cancelled);var data=frame(cancelled,false);
                field(RenderThread.class,"contextThread").set(null,new Thread());
                try{cancelled.prepareMesh(data,true);}finally{field(RenderThread.class,"contextThread").set(null,Thread.currentThread());}
                ModelSession replacement=create(0);stage(replacement);
                check(cancelled.retired&&Main.completeCapture(null,cancelled)==original,"Superseded candidate revived");
                drain();check(!cancelled.ready&&cancelled.viewpointModel==null,"Late queued upload resurrected retired candidate");
                check(Main.completeCapture(null,replacement)==original,"Replacement committed before GPU ready");
                replacement.prepareMesh(frame(replacement,false),true);check(Main.completeCapture(null,replacement)==replacement&&original.retired,"Ready replacement did not commit");
                drain();original=replacement;
            }
            ModelSession cancelling=create(softRow);stage(cancelling);var cancelledFrame=frame(cancelling,false);
            field(RenderThread.class,"contextThread").set(null,new Thread());
            try{cancelling.prepareMesh(cancelledFrame,true);}finally{field(RenderThread.class,"contextThread").set(null,Thread.currentThread());}
            Main.disable(null);drain();check(cancelling.retired&&Main.session(null)==null&&cancelling.viewpointModel==null,"Disable did not cancel pending GPU upload");
            int[] released={0};RuntimeException first=new IllegalStateException("injected-first"),second=new IllegalStateException("injected-last");
            try{ResourceCleanup.close(()->{released[0]++;throw first;},()->released[0]++,()->{released[0]++;throw second;});throw new AssertionError("Cleanup error disappeared");}
            catch(RuntimeException e){check(e==first&&e.getSuppressed().length==1&&released[0]==3,"Cleanup skipped later owners or lost failures");}
            ModelSession release=create(softRow);release.prepareMesh(frame(release,false),true);
            TextureFactory textures=(TextureFactory)field(ViewpointModel.class,"textures").get(release.viewpointModel);
            textures.close();textures.textures.put("injected-cleanup-failure",null);
            release.retire();release.retire();drain();
            check(release.viewpointModel==null,"Retirement did not clear ownership after injected failure");
            check(ChestSoftBody.liveWorlds()==0,"Cleanup failure blocked chest release");
            JSONObject stats=new JSONObject(api.GetRuntimeStats());for(String key:stats.keySet())check(stats.getLong(key)==0,"Native leak: "+stats);
            check(GL11.glGetError()==0,"GL error");grip.close();
            JSONObject result=new JSONObject().put("status","PASS_STAGED_SWITCH_FAILURE_SETTINGS_AND_RETIREMENT").put("assertions",checks).put("replacement_cycles",4)
                .put("remaining_native_objects",stats).put("remaining_chest_worlds",ChestSoftBody.liveWorlds()).put("real_render_queue",true).put("real_hidden_gpu_context",true)
                .put("actor_identity_substituted",true).put("game_started",false).put("actual_gameplay_tested",false);
            Files.writeString(out.resolve("result.json"),result.toString(2));System.out.println(result);
        }finally{Main.clear();GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
