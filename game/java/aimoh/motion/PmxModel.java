package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** One original PMX and generated model data, consumed by the same action core. */
public final class PmxModel implements AutoCloseable {
    private final NativeFunc api;
    private final long handle;
    private final WorldPoseRetargeter core;
    private final Profile profile;
    private final ModelAppearance appearance;
    private final ChestSoftBody chest;
    private boolean closed;

    private static final class Profile {
        int format, bones, source_root, target_root, source_motion;
        long vertices;
        String pmx_sha256;
        int[] source_ids, target_ids, parents, source_arms, target_arms;
        int[] palm_source_hands, palm_source_knuckles, palm_target_hands, palm_target_knuckles;
        float[] initial_positions, source_reference, source_bend, neutral_qps;
        float unit_scale;
        boolean palm_ready;
        boolean physics_enabled;
        JSONObject appearance;
        JSONObject appearance_controls;
        final Map<String,float[]> weapon_tuning = new HashMap<>();

        Profile(JSONObject j) {
            format=j.getInt("format"); bones=j.getInt("bones"); vertices=j.getLong("vertices");
            source_root=j.getInt("source_root"); target_root=j.getInt("target_root");
            pmx_sha256=j.getString("pmx_sha256"); unit_scale=(float)j.getDouble("unit_scale");
            palm_ready=j.getBoolean("palm_ready");
            JSONObject physics=j.optJSONObject("physics");
            physics_enabled=physics!=null && physics.optBoolean("enabled",false);
            appearance=j.optJSONObject("appearance");
            appearance_controls=j.optJSONObject("appearance_controls");
            source_ids=ints(j,"source_ids"); target_ids=ints(j,"target_ids"); parents=ints(j,"parents");
            source_motion=-1;
            JSONArray names=j.getJSONArray("source_bone_names");
            for(int i=0;i<names.length();i++)if("Bip01_Pelvis".equals(names.getString(i))){source_motion=i;break;}
            if(source_motion<0)throw new IllegalArgumentException("Source skeleton is missing Bip01_Pelvis");
            source_arms=ints(j,"source_arms"); target_arms=ints(j,"target_arms");
            palm_source_hands=ints(j,"palm_source_hands"); palm_source_knuckles=ints(j,"palm_source_knuckles");
            palm_target_hands=ints(j,"palm_target_hands"); palm_target_knuckles=ints(j,"palm_target_knuckles");
            initial_positions=floats(j,"initial_positions"); source_reference=floats(j,"source_reference");
            source_bend=floats(j,"source_bend"); neutral_qps=floats(j,"neutral_qps");
            JSONObject tuning=j.optJSONObject("weapon_tuning");
            if (tuning != null) for (String key : tuning.keySet()) weapon_tuning.put(key,floats(tuning,key));
        }
        private static int[] ints(JSONObject j,String name) {
            JSONArray a=j.getJSONArray(name); int[] result=new int[a.length()];
            for (int i=0;i<result.length;i++) result[i]=a.getInt(i);
            return result;
        }
        private static float[] floats(JSONObject j,String name) {
            JSONArray a=j.getJSONArray(name); float[] result=new float[a.length()];
            for (int i=0;i<result.length;i++) {
                result[i]=(float)a.getDouble(i);
                if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Non-finite profile data");
            }
            return result;
        }
    }

    private PmxModel(NativeFunc api, long handle, WorldPoseRetargeter core, Profile profile) throws IOException {
        this.api = api; this.handle = handle; this.core = core; this.profile = profile;
        appearance=new ModelAppearance(api,handle);
        if (profile.appearance!=null) appearance.configure(profile.appearance.toString(),profile.appearance_controls==null?Wardrobe.empty():profile.appearance_controls.toString());
        chest=ChestSoftBody.load(api,handle,new JSONObject().put("pmx_sha256",profile.pmx_sha256).put("unit_scale",profile.unit_scale).put("bones",profile.bones).put("vertices",profile.vertices));
    }

    public static PmxModel load(NativeFunc api, Path pmx, Path generatedProfile) throws IOException {
        Profile p = new Profile(new JSONObject(Files.readString(generatedProfile)));
        if (p.format != 1 || p.bones <= 0 || p.vertices <= 0 ||
            !Float.isFinite(p.unit_scale) || p.unit_scale <= 0)
            throw new IOException("Invalid generated PMX profile");
        try (var input = Files.newInputStream(pmx)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536];
            for (int n; (n = input.read(block)) >= 0;) digest.update(block, 0, n);
            if (!HexFormat.of().formatHex(digest.digest()).equals(p.pmx_sha256))
                throw new IOException("Profile belongs to a different PMX");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Path modelPath = pmx.toAbsolutePath();
        long handle = api.LoadModelPMX(modelPath.toString(), modelPath.getParent().toString(), 3);
        if (handle == 0) throw new IOException("Native PMX load failed");
        try {
            if (api.GetBoneCount(handle) != p.bones || api.GetVertexCount(handle) != p.vertices)
                throw new IOException("Native PMX structure differs from profile");
            api.SetPhysicsEnabled(handle, false); api.SetAutoBlinkEnabled(handle, false);
            if (!api.HasPhysics(handle)) api.InitPhysics(handle);
            api.SetPZLegIKSuppressed(handle, true); api.InitGpuSkinningData(handle);
            WorldPoseRetargeter core = new WorldPoseRetargeter(api, handle, p.source_ids, p.target_ids,
                p.parents, p.initial_positions, p.source_reference, p.neutral_qps, p.unit_scale,
                p.source_root, p.target_root, p.source_motion);
            if (p.palm_ready) core.calibratePalms(p.palm_source_hands, p.palm_source_knuckles,
                p.palm_target_hands, p.palm_target_knuckles);
            core.apply(p.source_bend); core.calibrateGripAxes(p.target_arms);
            core.setPhysicsEnabled(p.physics_enabled);
            return new PmxModel(api, handle, core, p);
        } catch (IOException | RuntimeException | Error e) {
            api.DeleteModel(handle);
            throw e;
        }
    }

    public synchronized WorldPoseRetargeter.GripResult[] apply(float[] finalSourcePose, OzzGrip grip) {
        return apply(finalSourcePose,grip,0);
    }

    public synchronized WorldPoseRetargeter.GripResult[] apply(float[] finalSourcePose, OzzGrip grip,float seconds) {
        return apply(finalSourcePose,grip,seconds,-1,-1);
    }
    public synchronized WorldPoseRetargeter.GripResult[] apply(float[] finalSourcePose,OzzGrip grip,float seconds,int sourceAnchor,int targetAnchor) {
        if (closed) throw new IllegalStateException("Closed PMX model");
        WorldPoseRetargeter.GripResult[] result;
        if (grip != null && profile.palm_ready)
            result=core.applyWithGrip(finalSourcePose, grip, profile.source_arms, profile.target_arms, .97f,seconds,sourceAnchor,targetAnchor);
        else {core.apply(finalSourcePose,seconds);result=new WorldPoseRetargeter.GripResult[0];}
        if(chest!=null){chest.enabled(core.physicsEnabled());chest.evaluate(seconds);}
        return result;
    }

    public long handle() { return handle; }
    public WorldPoseRetargeter retargeter() { return core; }
    public ModelAppearance appearance() { return appearance; }
    public synchronized void applySettings(String json) {
        if(closed)throw new IllegalStateException("Closed PMX model");
        JSONObject settings=new JSONObject(json),tuning=settings.optJSONObject("weapon_tuning");
        Map<String,float[]> next=new HashMap<>();
        if(tuning!=null)for(String key:tuning.keySet()) {
            float[] matrix=Profile.floats(tuning,key);
            ItemCalibration.apply(new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},matrix);next.put(key,matrix);
        }
        JSONObject state=settings.getJSONObject("appearance"),controls=settings.optJSONObject("appearance_controls");
        appearance.configure(state.toString(),controls==null?Wardrobe.empty():controls.toString());
        if(tuning!=null){profile.weapon_tuning.clear();profile.weapon_tuning.putAll(next);}
        JSONObject physics=settings.optJSONObject("physics");if(physics!=null)core.setPhysicsEnabled(physics.optBoolean("enabled",false));
        JSONObject soft=settings.optJSONObject("chest_physics");if(soft!=null&&chest!=null)chest.settings(soft);
    }
    public synchronized void setPhysicsEnabled(boolean enabled) { core.setPhysicsEnabled(enabled); }
    public synchronized boolean physicsEnabled() { return core.physicsEnabled(); }
    public synchronized void resetPhysics() { core.resetPhysics();if(chest!=null)chest.reset(); }
    public synchronized boolean hasChestPhysics(){return chest!=null&&chest.available();}
    public synchronized JSONObject chestSettings(){return chest==null?null:chest.settings();}
    public synchronized double chestSlider(int index){return chest==null?0:chest.slider(index);}
    public synchronized void chestSlider(int index,double value){if(chest==null)throw new IllegalStateException("当前模型无胸部物理");chest.slider(index,value);}
    public synchronized void resetChestSettings(){if(chest!=null)chest.defaults();}
    public synchronized String chestError(){return chest==null?"":chest.error();}
    public synchronized void deformChest(float[] positions,float[] normals){if(chest!=null)chest.deform(positions,normals);}

    public synchronized float[] remapHeldItem(float[] sourceWorld, boolean left,
            float[] actualItemWorld, String calibrationKey) {
        if (closed) throw new IllegalStateException("Closed PMX model");
        int side=left?0:1;
        float[] base=core.remapHeldItem(sourceWorld,profile.palm_source_hands[side],
            profile.palm_target_hands[side],actualItemWorld);
        float[] adjustment=profile.weapon_tuning.get(calibrationKey);
        return adjustment==null?base:ItemCalibration.apply(base,adjustment);
    }

    @Override public synchronized void close() {
        if (!closed) { try{if(chest!=null)chest.close();}finally{api.DeleteModel(handle);closed=true;} }
    }
}
