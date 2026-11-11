package aimoh.pmxpreview;

import aimoh.motion.*;
import com.shiroha.mmdskin.NativeFunc;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.model.ModelMesh;

/** Visibility, history and FEM ownership in an isolated native/GL component, without the game. */
public final class PhysicsStreamingCheck {
    static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static PmxDrawMesh.FrameData frame(NativeFunc api,PmxModel model,int n,float[] materials){
        ByteBuffer p=ByteBuffer.allocateDirect(n*12).order(ByteOrder.nativeOrder()),b=ByteBuffer.allocateDirect(n*12).order(ByteOrder.nativeOrder());
        api.CopyDataToByteBuffer(p,api.GetPoss(model.handle()),p.capacity());api.CopyDataToByteBuffer(b,api.GetNormals(model.handle()),b.capacity());
        float[] points=new float[n*3],normals=new float[n*3];p.asFloatBuffer().get(points);b.asFloatBuffer().get(normals);model.deformChest(points,normals);
        return new PmxDrawMesh.FrameData(points,normals,materials);
    }
    static void bounds(){
        var f=new PmxDrawMesh.FrameData(new float[]{-4,8,2,3,-2,9},new float[6],new float[12]);
        Vector3f lo=new Vector3f(Float.POSITIVE_INFINITY),hi=new Vector3f(Float.NEGATIVE_INFINITY);f.includeBounds(lo,hi);
        if(!lo.equals(new Vector3f(-4,-2,2))||!hi.equals(new Vector3f(3,8,9)))throw new AssertionError("Cached bounds");
        var second=new PmxDrawMesh.FrameData(new float[]{6,9,4,13,0,11},new float[6],new float[12]);
        var blended=PmxDrawMesh.FrameData.interpolated(new float[]{1,8.5f,3,8,-1,10},new float[6],new float[12],null,f,second);
        lo.set(Float.POSITIVE_INFINITY);hi.set(Float.NEGATIVE_INFINITY);blended.includeBounds(lo,hi);
        if(!lo.equals(new Vector3f(-4,-2,2))||!hi.equals(new Vector3f(13,9,11)))throw new AssertionError("Interpolation union");
        try{new PmxDrawMesh.FrameData(new float[]{Float.NaN,0,0},new float[3],new float[12]);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
    }
    public static void main(String[]args)throws Exception{
        bounds();Path root=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        zombie.ZomboidFileSystem.instance.setCacheDir(out.resolve("User").toString());zombie.ZomboidFileSystem.instance.init();
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(32,32,"R116 component check",0,0);if(window==0)throw new AssertionError("GL context");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();zombie.core.skinnedmodel.model.VertexBufferObject.funcs=new zombie.core.VBO.GLBufferObject15();
            field(RenderThread.class,"isInitialized").set(null,true);field(RenderThread.class,"contextThread").set(null,Thread.currentThread());field(Main.class,"directory").set(null,root);
            Method init=Main.class.getDeclaredMethod("nativeRuntime");init.setAccessible(true);init.invoke(null);NativeFunc api=(NativeFunc)field(Main.class,"api").get(null);
            Path assets=root.resolve("media/aimoh_pmx");JSONArray checked=new JSONArray();
            for(Object object:new JSONObject(Files.readString(assets.resolve("registry.json"))).getJSONArray("models")){
                JSONObject row=(JSONObject)object;if(row.optBoolean("hidden"))continue;
                Path pmx=assets.resolve(row.getString("model")),profile=assets.resolve(row.getString("profile"));JSONObject data=new JSONObject(Files.readString(profile));
                if(!Set.of("pmx-0af216265749a22d","pmx-c7270af966e8aaf8","pmx-f5b2807685b18a60","pmx-4818f3bdda4ec18d").contains(row.getString("id")))continue;
                try(PmxModel model=PmxModel.load(api,pmx,profile);TextureFactory textures=new TextureFactory(true)){
                    float[] pose=new float[data.getJSONArray("source_reference").length()];for(int i=0;i<pose.length;i++)pose[i]=data.getJSONArray("source_reference").getFloat(i);
                    for(int i=0;i<8;i++)model.apply(pose,null,1f/60);
                    String[] files=model.appearance().readTextures();for(int i=0;i<files.length;i++)if(!files[i].isBlank())files[i]=pmx.getParent().resolve(files[i]).toString();
                    var baked=textures.prepare(files,model.appearance().readTextureFactors(),1,0);
                    try(PmxDrawMesh mesh=new PmxDrawMesh(api,model.handle(),baked,model.appearance())){
                        int vertices=data.getInt("vertices");float[] materials=model.appearance().readMaterials();var visible=frame(api,model,vertices,materials);mesh.upload(visible);
                        List<?> parts=(List<?>)field(PmxDrawMesh.class,"parts").get(mesh);int skipped=0,uploadedVertices=0;
                        for(Object part:parts){Class<?> type=part.getClass();ModelMesh gpu=(ModelMesh)field(type,"mesh").get(part);int mat=field(type,"material").getInt(part),at=mat*12;float[] shadow=model.appearance().readMaterials(false);
                            boolean show=(materials[at+3]>0&&materials[at+7]+materials[at+11]>0)||(shadow[at+3]>0&&shadow[at+7]+shadow[at+11]>0);
                            if(!show){if(field(type,"handle").get(part)!=null||gpu.modificationCount!=0)throw new AssertionError("Hidden first upload");skipped++;}else uploadedVertices+=((int[])field(type,"source").get(part)).length;
                        }
                        float[] hidden=new float[materials.length];mesh.uploadPrepared(frame(api,model,vertices,hidden),hidden);
                        int[] versions=new int[parts.size()];for(int i=0;i<parts.size();i++)versions[i]=((ModelMesh)field(parts.get(i).getClass(),"mesh").get(parts.get(i))).modificationCount;
                        var hiddenFrame=frame(api,model,vertices,hidden);mesh.uploadPrepared(hiddenFrame,hidden);for(int i=0;i<parts.size();i++)if(((ModelMesh)field(parts.get(i).getClass(),"mesh").get(parts.get(i))).modificationCount!=versions[i])throw new AssertionError("Hidden updated GPU");
                        float[] shown=materials.clone();for(int i=0;i<shown.length;i+=12){shown[i+3]=1;shown[i+7]=1;shown[i+11]=0;}
                        var returned=frame(api,model,vertices,shown);for(int i=0;i<returned.positions().length;i+=3)returned.positions()[i]+=3;
                        // Use a new validated snapshot after changing the test input.
                        returned=new PmxDrawMesh.FrameData(returned.positions(),returned.normals(),shown);mesh.uploadPrepared(returned,shown);
                        for(Object part:parts){Class<?> type=part.getClass();ByteBuffer buffer=(ByteBuffer)field(type,"vertices").get(part);int[] source=(int[])field(type,"source").get(part);int stride=field(type,"stride").getInt(part);
                            for(int i=0;i<source.length;i++)for(int k=0;k<3;k++)if(buffer.getFloat(i*stride+k*4)!=buffer.getFloat(i*stride+44+k*4))throw new AssertionError("Reappeared velocity history");
                        }
                        // Camera-hidden colour must still upload when the same material casts a shadow.
                        mesh.uploadPrepared(hiddenFrame,shown);for(Object part:parts)if(!field(part.getClass(),"continuous").getBoolean(part))throw new AssertionError("Shadow-only upload skipped");
                        int choices=0;String saved=model.appearance().stateJson();long identity=model.handle();
                        for(Object g:new JSONObject(model.appearance().controlsJson()).getJSONArray("groups")){
                            JSONObject group=(JSONObject)g;for(Object o:group.getJSONArray("options")){
                                model.appearance().choose(group.getString("id"),((JSONObject)o).getString("id"));model.apply(pose,null,1f/60);
                                float[] current=model.appearance().readMaterials(),shadow=model.appearance().readMaterials(false);
                                for(int i=0;i<parts.size();i++)versions[i]=((ModelMesh)field(parts.get(i).getClass(),"mesh").get(parts.get(i))).modificationCount;
                                mesh.uploadPrepared(frame(api,model,vertices,current),shadow);
                                for(int i=0;i<parts.size();i++){
                                    Object part=parts.get(i);Class<?> type=part.getClass();int at=field(type,"material").getInt(part)*12;
                                    boolean show=(current[at+3]>0&&current[at+7]+current[at+11]>0)||(shadow[at+3]>0&&shadow[at+7]+shadow[at+11]>0);
                                    int difference=((ModelMesh)field(type,"mesh").get(part)).modificationCount-versions[i];
                                    if(difference!=(show?1:0))throw new AssertionError("Actual wardrobe visibility upload");
                                }
                                if(model.handle()!=identity||!model.chestError().isEmpty())throw new AssertionError("Wardrobe recreated model or failed physics");choices++;
                            }
                        }
                        model.appearance().configure(saved);
                        checked.put(new JSONObject().put("id",row.getString("id")).put("hidden_parts_skipped",skipped).put("default_uploaded_vertices",uploadedVertices).put("all_part_vertices",mesh.compactVertexCount()).put("actual_wardrobe_options",choices));
                    }
                    if(model.hasChestPhysics()){
                        ChestSoftBody chest=(ChestSoftBody)field(PmxModel.class,"chest").get(model);model.chestSlider(3,0);chest.reset();for(int i=0;i<120;i++)model.apply(pose,null,1f/60);
                        if(!field(ChestSoftBody.class,"reset").getBoolean(chest))throw new AssertionError("Zero strength stepped FEM");
                        if(!model.chestError().isEmpty())throw new AssertionError(model.chestError());model.chestSlider(3,100);model.apply(pose,null,1f/60);if(!model.chestError().isEmpty()||field(ChestSoftBody.class,"reset").getBoolean(chest))throw new AssertionError("Strength resume");
                    }
                }
                if(ChestSoftBody.liveWorlds()!=0)throw new AssertionError("Soft world leaked");
                if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            }
            JSONObject stats=new JSONObject(api.GetRuntimeStats());for(String key:stats.keySet())if(stats.getLong(key)!=0)throw new AssertionError(stats.toString());((OzzGrip)field(Main.class,"grip").get(null)).close();
            Files.writeString(out.resolve("result.json"),new JSONObject().put("models",checked).put("remaining_native",stats).put("game_started",false).put("actual_gameplay_tested",false).toString(2));System.out.println("PASS_PHYSICS_STREAMING "+checked.length());
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
