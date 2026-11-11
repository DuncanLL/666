package aimoh.pmxpreview;

import aimoh.motion.*;
import com.shiroha.mmdskin.NativeFunc;
import java.lang.reflect.*;
import java.nio.file.*;
import org.json.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import zombie.core.opengl.RenderThread;

/** Real image decode/upload/free in a private hidden context, without starting the game. */
public class TextureRuntimeCheck {
    private static void set(Class<?> type,String name,Object value)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
    private static Object get(Class<?> type,String name)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(null);
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);
        zombie.ZomboidFileSystem.instance.setCacheDir(args[1]);zombie.ZomboidFileSystem.instance.init();
        if(!GLFW.glfwInit())throw new AssertionError("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(32,32,"R116 texture check",0,0);
        if(window==0)throw new AssertionError("Hidden GL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            zombie.core.skinnedmodel.model.VertexBufferObject.funcs=new zombie.core.VBO.GLBufferObject15();
            set(RenderThread.class,"isInitialized",true);set(RenderThread.class,"contextThread",Thread.currentThread());
            set(Main.class,"directory",root);
            Method initialize=Main.class.getDeclaredMethod("nativeRuntime");initialize.setAccessible(true);initialize.invoke(null);
            NativeFunc api=(NativeFunc)get(Main.class,"api");
            JSONArray registry=new JSONObject(Files.readString(root.resolve("media/aimoh_pmx/registry.json"))).getJSONArray("models");
            int deformMask=0;try{deformMask=PmxDrawMesh.class.getField("DEFORMED").getInt(null);}catch(NoSuchFieldException historical){}
            int checked=0;
            for(int cycle=0;cycle<Integer.parseInt(args[2]);cycle++)for(int r=0;r<registry.length();r++) {
                JSONObject row=registry.getJSONObject(r);
                Path base=root.resolve("media/aimoh_pmx"),pmx=base.resolve(row.getString("model")),profile=base.resolve(row.getString("profile"));
                JSONObject p=new JSONObject(Files.readString(profile));
                try(PmxModel model=PmxModel.load(api,pmx,profile);TextureFactory textures=new TextureFactory()) {
                    var source=p.getJSONArray("source_reference");float[] pose=new float[source.length()];
                    for(int i=0;i<pose.length;i++)pose[i]=source.getFloat(i);
                    for(int f=0;f<8;f++)model.apply(pose,null,1f/60);
                    String[] paths=model.appearance().readTextures();
                    for(int i=0;i<paths.length;i++)if(!paths[i].isBlank())paths[i]=pmx.getParent().resolve(paths[i]).toString();
                    PmxShading shading=PmxShading.load(pmx,p);float[] factors=model.appearance().readTextureFactors();
                    var baked=textures.prepare(paths,factors,1,0,shading);long uploads=textures.uploads;
                    if(textures.prepare(paths,factors,1,0,shading)!=baked)throw new AssertionError("Stable frame rebuilt bindings");
                    textures.prepare(paths,factors,2,0,shading);
                    if(textures.uploads!=uploads)throw new AssertionError("Unchanged factors uploaded textures again");
                    if(deformMask!=0){
                    int n=p.getInt("vertices");var pb=java.nio.ByteBuffer.allocateDirect(n*12);var nb=java.nio.ByteBuffer.allocateDirect(n*12);
                    api.CopyDataToByteBuffer(pb,api.GetPoss(model.handle()),n*12);api.CopyDataToByteBuffer(nb,api.GetNormals(model.handle()),n*12);
                    float[] points=new float[n*3],normals=new float[n*3];pb.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(points);nb.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(normals);
                    model.deformChest(points,normals);
                    try(PmxDrawMesh mesh=new PmxDrawMesh(api,model.handle(),baked,model.appearance(),shading)){
                        var frozen=new PmxDrawMesh.FrameData(points,normals,model.appearance().readMaterials());mesh.upload(frozen);
                        var draws=new viewpoint.render.ModelDraws();var placement=new org.joml.Matrix4f();
                        draws.add(null,null,null,placement,-1,-1,0,3);
                        int added=mesh.submitFrozen(draws,placement,frozen,index->new Object(),model.appearance().readMaterials(false),model.appearance().readBothFaces());
                        if(added==0||draws.flags(0)!=3)throw new AssertionError("PMX submission changed an unrelated draw");
                        for(int d=1;d<draws.count();d++)if((draws.flags(d)&deformMask)==0)throw new AssertionError("PMX draw omitted deformation ownership");
                    }
                    }
                    int error=GL11.glGetError();if(error!=0)throw new AssertionError("Texture GL error "+error);
                    System.out.println("CHECK "+cycle+" "+row.getString("id")+" "+baked.length);checked++;
                }
                if(ChestSoftBody.liveWorlds()!=0)throw new AssertionError("Leaked chest world");
            }
            JSONObject stats=new JSONObject(api.GetRuntimeStats());
            for(String key:stats.keySet())if(stats.getLong(key)!=0)throw new AssertionError("Leaked native object: "+stats);
            ((OzzGrip)get(Main.class,"grip")).close();
            if(deformMask!=0)System.out.println("PASS_OWNED_DEFORMATION_FLAGS "+checked);
            System.out.println("PASS_TEXTURE_RUNTIME "+checked);
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
