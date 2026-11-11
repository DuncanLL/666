package aimoh.pmxpreview;
import aimoh.motion.*;
import com.shiroha.mmdskin.NativeFunc;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Map;
import java.util.function.IntFunction;
import org.json.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import viewpoint.platform.IrisPacks;
import viewpoint.render.ModelDraws;
import zombie.core.opengl.RenderThread;

/** Same mesh, actual author geometry, asynchronous-readiness boundary; no game is launched. */
public final class MeshReadinessCheck {
    static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=Path.of(args[1]);boolean before=Boolean.parseBoolean(args[2]);
        zombie.ZomboidFileSystem.instance.setCacheDir(out.resolve("User").toString());zombie.ZomboidFileSystem.instance.init();
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);long window=GLFW.glfwCreateWindow(32,32,"R116 mesh readiness",0,0);if(window==0)throw new AssertionError("Hidden context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            zombie.core.skinnedmodel.model.VertexBufferObject.funcs=new zombie.core.VBO.GLBufferObject15();
            field(RenderThread.class,"isInitialized").setBoolean(null,true);field(RenderThread.class,"contextThread").set(null,Thread.currentThread());
            field(Main.class,"directory").set(null,root);Method init=Main.class.getDeclaredMethod("nativeRuntime");init.setAccessible(true);init.invoke(null);NativeFunc api=(NativeFunc)field(Main.class,"api").get(null);
            Path assets=root.resolve("media/aimoh_pmx");JSONObject row=new JSONObject(Files.readString(assets.resolve("registry.json"))).getJSONArray("models").getJSONObject(0);
            Path pmx=assets.resolve(row.getString("model")),profile=assets.resolve(row.getString("profile"));JSONObject p=new JSONObject(Files.readString(profile));int count=p.getInt("vertices");
            try(PmxModel model=PmxModel.load(api,pmx,profile);
                    TextureFactory textures=before?TextureFactory.class.getDeclaredConstructor().newInstance():new TextureFactory(true)){
                var ref=p.getJSONArray("source_reference");float[] pose=new float[ref.length()];for(int i=0;i<pose.length;i++)pose[i]=ref.getFloat(i);model.apply(pose,null,0);
                String[] paths=model.appearance().readTextures();for(int i=0;i<paths.length;i++)if(!paths[i].isBlank())paths[i]=pmx.getParent().resolve(paths[i]).toString();PmxShading shading=PmxShading.load(pmx,p);
                var material=textures.prepare(paths,model.appearance().readTextureFactors(),1,0,shading);
                ByteBuffer pb=ByteBuffer.allocateDirect(count*12).order(ByteOrder.nativeOrder()),nb=ByteBuffer.allocateDirect(count*12).order(ByteOrder.nativeOrder());
                api.CopyDataToByteBuffer(pb,api.GetPoss(model.handle()),count*12);api.CopyDataToByteBuffer(nb,api.GetNormals(model.handle()),count*12);float[] points=new float[count*3],normals=new float[count*3];pb.asFloatBuffer().get(points);nb.asFloatBuffer().get(normals);
                var frame=new PmxDrawMesh.FrameData(points,normals,model.appearance().readMaterials());
                IrisPacks.Active previous=IrisPacks.drawn();IrisPacks.linked(null);
                try(PmxDrawMesh mesh=new PmxDrawMesh(api,model.handle(),material,model.appearance(),shading)){
                    mesh.upload(frame);IrisPacks.linked(new IrisPacks.Active(null,Map.of("VP_DANRP_PIPELINE","true"),1));
                    if(before){
                        boolean reproduced=false;try{mesh.submitFrozen(new ModelDraws(),new Matrix4f(),frame,i->new Object(),model.appearance().readMaterials(false),model.appearance().readBothFaces());}catch(IllegalStateException e){if(!"Mesh not ready".equals(e.getMessage()))throw e;reproduced=true;}
                        if(!reproduced)throw new AssertionError("Old profile-switch failure did not reproduce");
                    }else{
                        Method ready=PmxDrawMesh.class.getMethod("readyMesh"),upload=PmxDrawMesh.class.getMethod("uploadPrepared",PmxDrawMesh.FrameData.class),submit=PmxDrawMesh.class.getMethod("submitPreparedFrozen",ModelDraws.class,Matrix4f.class,PmxDrawMesh.FrameData.class,IntFunction.class,float[].class,boolean[].class);
                        if(ready.invoke(mesh)!=null)throw new AssertionError("Unprepared topology was published");mesh.upload(frame);Object retained=ready.invoke(mesh);if(retained==null||retained==mesh)throw new AssertionError("Alternate topology not uploaded");
                        for(int i=0;i<12;i++){
                            IrisPacks.linked(i%2==0?null:new IrisPacks.Active(null,Map.of("VP_DANRP_PIPELINE","true"),i+2));Object selected=ready.invoke(mesh);if(selected==null)throw new AssertionError("Ready topology discarded on repeat switch");
                            upload.invoke(selected,frame);ModelDraws draws=new ModelDraws();int added=(int)submit.invoke(selected,draws,new Matrix4f(),frame,(IntFunction<Object>)(n->new Object()),model.appearance().readMaterials(false),model.appearance().readBothFaces());if(added<=0)throw new AssertionError("Prepared stream omitted draws");
                        }
                        IrisPacks.linked(null);upload.invoke(retained,frame);
                        ModelDraws oldFrame=new ModelDraws();if((int)submit.invoke(retained,oldFrame,new Matrix4f(),frame,(IntFunction<Object>)(n->new Object()),model.appearance().readMaterials(false),model.appearance().readBothFaces())<=0)throw new AssertionError("Captured stream changed beneath an older frame");
                    }
                }finally{IrisPacks.linked(previous);}
                if(!before){
                    ModelSession session=new ModelSession(api,(OzzGrip)field(Main.class,"grip").get(null),new Main.Entry(row.getString("id"),row.getString("label"),pmx,profile));
                    try{
                        session.materials=new ModelSession.Materials(1,0,session.model.appearance().readTextureFactors(),session.model.appearance().readMaterials(false),paths,session.model.appearance().readBothFaces());
                        session.prepareMesh(frame,true);if(!session.ready||session.failed||session.viewpointModel.prepared==null)throw new AssertionError("Initial session upload did not complete");
                        PmxDrawMesh retained=session.viewpointModel.prepared;IrisPacks.linked(new IrisPacks.Active(null,Map.of("VP_DANRP_PIPELINE","true"),30));
                        field(RenderThread.class,"contextThread").set(null,new Thread());
                        try{session.prepareMesh(frame,true);session.prepareMesh(frame,true);if(session.ready||session.failed||!field(ModelSession.class,"viewpointQueued").getBoolean(session))throw new AssertionError("Pending GPU work became a failed/ready session");}
                        finally{field(RenderThread.class,"contextThread").set(null,Thread.currentThread());}
                        Method drain=RenderThread.class.getDeclaredMethod("flushInvokeQueue");drain.setAccessible(true);drain.invoke(null);
                        if(!session.ready||session.failed||session.viewpointModel.prepared==retained||field(ModelSession.class,"viewpointQueued").getBoolean(session))throw new AssertionError("Queued session did not recover after upload");
                        PmxDrawMesh.class.getMethod("uploadPrepared",PmxDrawMesh.FrameData.class).invoke(retained,frame);
                    }finally{IrisPacks.linked(previous);session.retire();viewpoint.render.Retirement.drawn(10000);GL11.glFinish();viewpoint.render.Retirement.collect();}
                }
            }
            ((OzzGrip)field(Main.class,"grip").get(null)).close();JSONObject stats=new JSONObject(api.GetRuntimeStats());for(String key:stats.keySet())if(stats.getLong(key)!=0)throw new AssertionError(stats);
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            JSONObject result=new JSONObject().put("status",before?"OLD_PROFILE_SWITCH_MESH_NOT_READY_REPRODUCED":"PASS_READY_TOPOLOGY_AND_CAPTURED_STREAM_LIFETIME").put("profile_switches",before?1:12).put("real_session_queue_and_retirement_checked",!before).put("game_started",false).put("actual_gameplay_tested",false);Files.writeString(out.resolve("result.json"),result.toString(2));System.out.println(result);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
