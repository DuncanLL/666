package gfl2.runtime;

import com.shiroha.mmdskin.NativeFunc;
import gfl2.motion.PmxModel;
import gfl2.motion.WorldPoseRetargeter;
import java.lang.invoke.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import zombie.ZomboidFileSystem;

/** Snapshot every bone, including PMX helpers, through the game's native reader. */
public final class RetargetParityCheck {
    public static void main(String[] args)throws Throwable{
        Path workshop=Path.of(args[0]), out=Files.createDirectories(Path.of(args[1]));
        var fs=ZomboidFileSystem.instance;fs.setCacheDir(out.resolve("isolated").toString());fs.init();
        fs.setModFoldersOrder("mods,workshop");
        try(var dirs=Files.list(workshop)){
            for(Path dir:dirs.sorted().toList()){
                String id=dir.getFileName().toString();Path mod=dir.resolve("Contents/mods").resolve(id);
                if(id.startsWith("GFL2")&&Files.isRegularFile(mod.resolve("42/mod.info"))){
                    fs.setModIdToDir(id,mod.toString());fs.loadMod(id);fs.getModIDs().add(id);
                }
            }
        }
        var init=Main.class.getDeclaredMethod("nativeRuntime");init.setAccessible(true);init.invoke(null);
        var apiField=Main.class.getDeclaredField("api");apiField.setAccessible(true);var api=(NativeFunc)apiField.get(null);
        var registry=Main.class.getDeclaredField("entries");registry.setAccessible(true);
        var body=MethodHandles.privateLookupIn(WorldPoseRetargeter.class,MethodHandles.lookup())
            .findVirtual(WorldPoseRetargeter.class,"applyBody",MethodType.methodType(void.class,float[].class,boolean.class));
        JSONArray summary=new JSONArray();
        for(Object row:(List<?>)registry.get(null)){
            Main.Entry e=(Main.Entry)row;JSONObject profile=new JSONObject(Files.readString(e.profile()));
            try(var m=PmxModel.load(api,e.model(),e.profile());var file=Files.newOutputStream(out.resolve(e.id()+".f32"))){
                if (Boolean.getBoolean("gfl2.checkWardrobeReads")) {
                    var appearance=m.appearance();var groups=new JSONObject(appearance.controlsJson()).getJSONArray("groups");
                    var persisted=new JSONObject(appearance.stateJson());
                    if(((int)appearance.getClass().getMethod("groupCount").invoke(appearance))!=groups.length())throw new AssertionError("Wardrobe group count");
                    for(int g=0;g<groups.length();g++){
                        var group=groups.getJSONObject(g);var options=group.getJSONArray("options");
                        if(!((String)appearance.getClass().getMethod("groupLabel",int.class).invoke(appearance,g)).equals(group.optString("label",group.getString("id"))) ||
                            ((int)appearance.getClass().getMethod("optionCount",int.class).invoke(appearance,g))!=options.length())throw new AssertionError("Wardrobe group");
                        String selected=persisted.getJSONObject("choices").getString(group.getString("id"));
                        for(int o=0;o<options.length();o++){
                            var option=options.getJSONObject(o);String id=option.getString("id");
                            if(!((String)appearance.getClass().getMethod("optionLabel",int.class,int.class).invoke(appearance,g,o)).equals(option.optString("label",id))
                                || (((int)appearance.getClass().getMethod("selectedOption",int.class).invoke(appearance,g))==o)!=selected.equals(id))throw new AssertionError("Wardrobe choice");
                        }
                    }
                    var visible=persisted.getJSONArray("material_visibility");
                    for(int i=0;i<visible.length();i++)if(((boolean)appearance.getClass().getMethod("materialVisible",int.class).invoke(appearance,i))!=visible.getBoolean(i))throw new AssertionError("Wardrobe visibility");
                }
                var rt=m.retargeter();float[] pose=new float[profile.getJSONArray("source_reference").length()];
                ByteBuffer palette=ByteBuffer.allocateDirect(profile.getInt("bones")*64);
                byte[] bytes=new byte[palette.capacity()];var evals=new JSONArray();
                for(String key:List.of("source_reference","source_bend","idle")){
                    if(key.equals("idle"))ByteBuffer.wrap(Files.readAllBytes(Path.of(args[2]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pose);
                    else{var input=profile.getJSONArray(key);for(int i=0;i<pose.length;i++)pose[i]=input.getFloat(i);}
                    body.invokeExact(rt,pose,true);
                    palette.clear();if(api.CopySkinningMatricesToBuffer(m.handle(),palette)!=profile.getInt("bones"))throw new AssertionError(e.id());
                    palette.get(bytes);file.write(bytes);evals.put(rt.animationEvaluations());
                }
                summary.put(new JSONObject().put("model",e.id()).put("evaluations",evals));
            }
            if(e.id().equals(args[3])){
                var room=Main.createRoomPreview(e.id());
                try{
                    var names=profile.getJSONArray("source_bone_names");int[] indices=new int[names.length()];
                    for(int j=0;j<indices.length;j++)indices[j]=j;
                    float[] idle=new float[names.length()*16];ByteBuffer.wrap(Files.readAllBytes(Path.of(args[2]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(idle);
                    float[] handPose=room.idleHand.apply(idle,indices,0,true);
                    room.model.apply(handPose,null,0);
                    int bones=profile.getInt("bones");
                    ByteBuffer palette=ByteBuffer.allocateDirect(bones*64);
                    if(api.CopySkinningMatricesToBuffer(room.model.handle(),palette)!=bones)throw new AssertionError();
                    var hands=profile.getJSONArray("palm_target_hands");byte[] saved=new byte[hands.length()*64];
                    for(int i=0;i<hands.length();i++){palette.position(hands.getInt(i)*64);palette.get(saved,64*i,64);}
                    Files.write(out.resolve("browse-hands.f32"),saved);
                }finally{room.model.close();}
            }
        }
        Files.writeString(out.resolve("models.json"),summary.toString(2));
        System.out.println(summary.length()+" complete palettes");
    }
}
