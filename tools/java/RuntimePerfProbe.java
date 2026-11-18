package gfl2.runtime;

import com.shiroha.mmdskin.NativeFunc;
import gfl2.motion.PmxDrawMesh.FrameData;
import gfl2.motion.WorldPoseRetargeter;
import java.lang.invoke.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import viewpoint.render.*;
import zombie.ZomboidFileSystem;

public final class RuntimePerfProbe {
    private static Object field(Object object,String name)throws Exception{
        var f=(object instanceof Class<?> c?c:object.getClass()).getDeclaredField(name);
        f.setAccessible(true);return f.get(object instanceof Class<?>?null:object);
    }
    private static double median(long[] ns){Arrays.sort(ns);return ns[ns.length/2]/1e6;}
    private static FrameData capture(NativeFunc api,gfl2.motion.PmxModel model,gfl2.motion.PmxShading dan,int count,float[] pose){
        model.apply(pose,null,0);
        ByteBuffer buf=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder());
        float[] positions=new float[count],normals=new float[count];
        api.CopyDataToByteBuffer(buf,api.GetPoss(model.handle()),count*4);buf.asFloatBuffer().get(positions);
        api.CopyDataToByteBuffer(buf,api.GetNormals(model.handle()),count*4);buf.asFloatBuffer().get(normals);
        float[] materials=model.appearance().readMaterials();
        return new FrameData(positions,normals,materials,dan.freeze(api,model.handle(),materials,positions,null));
    }
    public static void main(String[] args)throws Throwable{
        Path mods=Path.of(args[0]),out=Path.of(args[2]);
        Files.createDirectories(out);
        var fs=ZomboidFileSystem.instance;fs.setCacheDir(out.resolve("user").toString());fs.init();
        fs.setModFoldersOrder("mods,workshop");
        try(var dir=Files.list(mods)){
            for(Path folder:dir.sorted().toList()){
                String id=folder.getFileName().toString();
                Path mod=folder.resolve("Contents/mods").resolve(id);
                if(!Files.isRegularFile(mod.resolve("42/mod.info")) || !id.startsWith("GFL2"))continue;
                fs.setModIdToDir(id,mod.toString());fs.loadMod(id);fs.getModIDs().add(id);
            }
        }
        var registry=Main.enabledCharacterPacks();
        JSONObject result=new JSONObject().put("pack_count",registry.size());
        var nativeInit=Main.class.getDeclaredMethod("nativeRuntime");nativeInit.setAccessible(true);nativeInit.invoke(null);
        var api=(NativeFunc)field(Main.class,"api");
        long[] loads=new long[7];
        for(int i=0;i<loads.length;i++){
            long start=System.nanoTime();
            ModelSession s=Main.createRoomPreview(args[1]);loads[i]=System.nanoTime()-start;
            result.put("preview_bullet",api.HasPhysics(s.model.handle())).put("preview_chest",s.model.hasChestPhysics())
                .put("bullet_worlds",new JSONObject(api.GetRuntimeStats()).getInt("worlds"))
                .put("chest_worlds",gfl2.motion.ChestSoftBody.liveWorlds());
            s.model.close();
        }
        result.put("room_cold_ms",loads[0]/1e6).put("room_hot_ms",median(Arrays.copyOfRange(loads,1,loads.length)));
        var entries=(List<?>)field(Main.class,"entries");
        Main.Entry entry=(Main.Entry)entries.stream().filter(e->((Main.Entry)e).id().equals(args[1])).findFirst().orElseThrow();
        var model=gfl2.motion.PmxModel.load(api,entry.model(),entry.profile());
        try{
            JSONObject profile=new JSONObject(Files.readString(entry.profile()));
            int vertices=profile.getInt("vertices"),bones=profile.getInt("bones"),count=vertices*3;
            result.put("vertices",vertices);
            var poses=profile.getJSONArray("source_reference");float[] pose=new float[poses.length()];
            for(int i=0;i<pose.length;i++)pose[i]=poses.getFloat(i);
            var body=MethodHandles.privateLookupIn(WorldPoseRetargeter.class,MethodHandles.lookup())
                .findVirtual(WorldPoseRetargeter.class,"applyBody",MethodType.methodType(void.class,float[].class,boolean.class));
            var rt=model.retargeter(); Files.writeString(out.resolve("metadata.json"),api.GetBoneMetadata(model.handle()));
            for(int i=0;i<150;i++)body.invokeExact(rt,pose,true);
            var mx=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
            long[] bodyNs=new long[121];long allocated=0;
            for(int batch=0;batch<4;batch++){
                long bytes=mx.getCurrentThreadAllocatedBytes();
                for(int i=0;i<bodyNs.length;i++){long start=System.nanoTime();body.invokeExact(rt,pose,true);bodyNs[i]=System.nanoTime()-start;}
                allocated=mx.getCurrentThreadAllocatedBytes()-bytes;
            }
            result.put("body_bytes_per_call",allocated/(double)bodyNs.length)
                .put("body_ms",median(bodyNs)).put("evaluations",rt.animationEvaluations());
            var buf=ByteBuffer.allocateDirect(bones*64).order(ByteOrder.nativeOrder());
            try(var stream=Files.newOutputStream(out.resolve("matrices.f32"))){
                for(String k:List.of("source_reference","source_bend","idle")){
                    if(k.equals("idle")){ByteBuffer.wrap(Files.readAllBytes(Path.of(args[4]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pose);}
                    else {var a=profile.getJSONArray(k);for(int i=0;i<pose.length;i++)pose[i]=a.getFloat(i);}
                    body.invokeExact(rt,pose,true);if(api.CopySkinningMatricesToBuffer(model.handle(),buf)!=bones)throw new AssertionError();
                    byte[] actual=new byte[buf.capacity()];buf.clear();buf.get(actual);stream.write(actual);
                }
            }
            var shading=gfl2.motion.PmxShading.load(entry.model(),profile);
            var dan=(gfl2.motion.PmxShading)field(shading,"danrpVariant");
            viewpoint.platform.IrisPacks.linked(new viewpoint.platform.IrisPacks.Active(null,Map.of("VP_DANRP_PIPELINE","true"),1));
            var first=capture(api,model,dan,count,pose);
            for(int i=0;i<pose.length;i++)pose[i]=poses.getFloat(i);
            var second=capture(api,model,dan,count,pose);
            if(second.shadingHead()[26]!=42137 || second.shadingHead().length<count)throw new AssertionError("No DanRP history");
            var phase=PmxPresentation.timing(second,first,null,.37f,false);var display=new PmxPresentation();
            for(int i=0;i<150;i++)display.sample(second,phase,false);
            if(Boolean.getBoolean("gfl2.measureFinite")){
                var counter=Class.forName("FrameQueryCounter");
                counter.getMethod("beginFinite").invoke(null);
                display.sample(second,phase,false);
                result.put("interpolation_isFinite_visits",counter.getMethod("endFinite").invoke(null));
            }
            long[] ns=new long[181];
            for(int i=0;i<ns.length;i++){long startNs=System.nanoTime();var frame=display.sample(second,phase,false);ns[i]=System.nanoTime()-startNs;if(!Float.isFinite(frame.positions()[5]))throw new AssertionError();}
            result.put("sample_ms",median(ns)).put("frame_input_floats",2*count+second.shadingHead().length+second.materials().length);
        }finally{model.close();}
        Files.writeString(out.resolve("runtime.json"),result.toString(2));
        if(!GLFW.glfwInit())throw new AssertionError("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);long window=GLFW.glfwCreateWindow(32,32,"catalog probe",0,0);
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            Path pack=Path.of(args[3]);long started=System.nanoTime();
            var data=new Gfl2PackData((viewpoint.iris.SourceFiles)name->{
                Path file=pack.resolve("shaders").resolve(name);return Files.exists(file)?Files.readString(file):null;
            });
            try(var materials=new Gfl2PackMaterials(data)){
                result.put("catalog_materials_ms",(System.nanoTime()-started)/1e6)
                    .put("initial_buffers",((Map<?,?>)field(materials,"materialBuffers")).size())
                    .put("material_count",materials.materialCount()).put("identity",data.identity);
                if(!materials.verifyMaterialUploads())throw new AssertionError("Material UBO readback differs");
            }
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
        Files.writeString(out.resolve("runtime.json"),result.toString(2));System.out.println(result);
    }
}
