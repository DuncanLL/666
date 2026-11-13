package aimoh.pmxpreview;

import aimoh.motion.OzzGrip;
import aimoh.motion.ChestSoftBody;
import com.shiroha.mmdskin.NativeFunc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

public class Main {
    public record Entry(String id,String label,Path model,Path profile) {
        public Entry(String label,Path model,Path profile){this(model.toString(),label,model,profile);}
    }
    private record Pending(Entry entry,String settings,boolean remote) {}
    private static final ArrayList<Entry> entries=new ArrayList<>();
    private static final HashSet<String> hiddenEntries=new HashSet<>();
    record CharacterPack(String id,Path media) {}
    private static List<CharacterPack> enabledPacks=List.of();
    private static final IdentityHashMap<IsoGameCharacter,ModelSession> active=new IdentityHashMap<>();
    private static final IdentityHashMap<IsoGameCharacter,ModelSession> preparing=new IdentityHashMap<>();
    private static final HashSet<IsoGameCharacter> switchFailures=new HashSet<>();
    private static final IdentityHashMap<IsoGameCharacter,Pending> pending=new IdentityHashMap<>();
    private static volatile IdentityHashMap<IsoGameCharacter,SourcePoseGuard.AnimationPose> poseOwners=new IdentityHashMap<>();
    private static String lastError="";
    private static NativeFunc api;
    private static OzzGrip grip;
    private static Path directory;

    public static void main(String[] args) {
        // ZombieBuddy retransforms loaded game classes when the package is dispatched.
        // Resolve these two lazy stock renderer classes before it applies the advice.
        ClassLoader loader=Main.class.getClassLoader();
        for(String type:new String[]{"zombie.core.skinnedmodel.model.Model","zombie.ui.UI3DModel$Drawer"}) {
            try { Class.forName(type,false,loader); }
            catch(ClassNotFoundException | LinkageError failure) { error(failure); }
        }
        System.out.println("[GFL2Exilium] Shared player appearance");
    }

    private static void refreshPoseOwners() {
        IdentityHashMap<IsoGameCharacter,SourcePoseGuard.AnimationPose> next=new IdentityHashMap<>();
        for(var owners:java.util.List.of(active.keySet(),preparing.keySet(),pending.keySet()))for(IsoGameCharacter actor:owners){
            SourcePoseGuard.AnimationPose pose=poseOwners.get(actor);
            next.put(actor,pose==null?new SourcePoseGuard.AnimationPose():pose);
        }
        poseOwners=next;
    }
    static SourcePoseGuard.AnimationPose sourceAnimationPose(AnimationPlayer player) {
        IsoGameCharacter actor=player.getIsoGameCharacter();
        return actor!=null&&actor.getAnimationPlayer()==player?poseOwners.get(actor):null;
    }

    private static Path locate() throws Exception {
        String root=ZomboidFileSystem.instance.getModDir("AimohPmxPreview");
        if(root==null)throw new IllegalStateException("游戏尚未登记 AimohPmxPreview 模组目录");
        Path[] candidates=new Path[]{Path.of(root),Path.of(root).resolve("42")};
        for (Path p:candidates) if (Files.isRegularFile(p.resolve("media/aimoh_pmx/registry.json"))) return p.toAbsolutePath();
        throw new IllegalStateException("找不到测试 MOD 的模型目录");
    }
    private static Path asset(Path root,String relative) {
        Path result=root.resolve(relative).normalize();
        if (!result.startsWith(root) || !Files.isRegularFile(result)) throw new IllegalArgumentException("缺少模型资源："+relative);
        return result;
    }
    private static synchronized void configure() throws Exception {
        if (directory!=null) return;
        Path d=locate(),root=d.resolve("media/aimoh_pmx").normalize();
        JSONObject registry=new JSONObject(Files.readString(root.resolve("registry.json")));
        ArrayList<Entry> loaded=new ArrayList<>();HashSet<String> hidden=new HashSet<>();
        readEntries(root,registry.getJSONArray("models"),loaded,hidden);
        ArrayList<CharacterPack> packs=new ArrayList<>();
        JSONObject declared=registry.optJSONObject("packs");
        if(declared!=null)for(String mod:declared.keySet().stream().sorted().toList()){
            if(!mod.matches("[A-Za-z0-9_-]+")||!ZomboidFileSystem.instance.getModIDs().contains(mod))continue;
            try {
                Path modRoot=Path.of(ZomboidFileSystem.instance.getModDir(mod)).toAbsolutePath().normalize();
                Path media=modRoot.resolve("media");
                String relative="aimoh_pmx/packs/"+mod+".json";
                if(!Files.isRegularFile(media.resolve(relative)))media=modRoot.resolve("42/media");
                Path file=asset(media,relative);
                JSONObject pack=new JSONObject(Files.readString(file));
                if(pack.getInt("format")!=1||!mod.equals(pack.getString("mod")))throw new IllegalArgumentException("Invalid pack identity");
                ArrayList<Entry> added=new ArrayList<>();HashSet<String> packHidden=new HashSet<>();
                readEntries(media.resolve("aimoh_pmx"),pack.getJSONArray("models"),added,packHidden);
                var ids=added.stream().map(Entry::id).toList();
                if(ids.isEmpty()||!new HashSet<>(declared.getJSONArray(mod).toList()).equals(new HashSet<>(ids)))
                    throw new IllegalArgumentException("Unexpected character pack models");
                if(loaded.stream().anyMatch(entry->ids.contains(entry.id())))throw new IllegalArgumentException("Duplicate model ID");
                loaded.addAll(added);hidden.addAll(packHidden);packs.add(new CharacterPack(mod,media));
            }catch(Exception e){System.err.println("[GFL2Exilium] Character pack "+mod+": "+e);}
        }
        entries.clear();entries.addAll(loaded);hiddenEntries.clear();hiddenEntries.addAll(hidden);
        enabledPacks=List.copyOf(packs);directory=d;
    }
    static synchronized List<CharacterPack> enabledCharacterPacks() throws Exception {
        configure();
        return enabledPacks;
    }
    private static void readEntries(Path root,JSONArray models,ArrayList<Entry> loaded,HashSet<String> hidden) throws Exception {
        for (int i=0;i<models.length();i++) {
            JSONObject j=models.getJSONObject(i);
            Path profile=asset(root,j.getString("profile"));
            String id=j.optString("id","pmx-"+new JSONObject(Files.readString(profile)).getString("pmx_sha256").substring(0,16));
            if(loaded.stream().anyMatch(entry->entry.id().equals(id)))throw new IllegalArgumentException("Duplicate model ID");
            loaded.add(new Entry(id,j.getString("label"),asset(root,j.getString("model")),profile));
            if(j.optBoolean("hidden",false))hidden.add(id);
        }
    }
    public static synchronized String refreshLibrary() {
        Path previous=directory;directory=null;
        try{configure();return "";}catch(Throwable e){directory=previous;error(e);return lastError;}
    }
    private static synchronized void nativeRuntime() throws Exception {
        if (api!=null) return;
        configure();
        try { if(NativeFunc.aimohFacadeVersion()!=1)throw new LinkageError("facade version"); }
        catch(LinkageError e) { throw new IllegalStateException("另一个 PMX 引擎已占用同名原生接口；本次测试请只启用本测试版",e); }
        Path nativeDirectory=NativeBundle.prepare(directory.resolve("media/java/client/native"));
        for(String dependency:new String[]{"libwinpthread-1.dll","libgcc_s_seh-1.dll","libstdc++-6.dll","AimohMmdUpstream.dll"})
            System.load(nativeDirectory.resolve(dependency).toString());
        NativeFunc loaded=new NativeFunc();
        if(Files.isRegularFile(nativeDirectory.resolve("AimohChestSoftBody.dll")))ChestSoftBody.initialize(nativeDirectory.resolve("AimohChestSoftBody.dll"));
        grip=new OzzGrip(nativeDirectory.resolve("AimohGripOzz.dll"));api=loaded;
        System.out.println("[GFL2Exilium] native="+api.GetVersion()+" assets="+directory);
    }
    static boolean hasAppearance(IsoGameCharacter actor) { return poseOwners.containsKey(actor); }
    static synchronized ModelSession session(IsoGameCharacter actor) { return active.get(actor); }
    static synchronized ModelSession sessionForCapture(IsoGameCharacter actor) {
        Pending request=pending.remove(actor);
        if(request!=null) {
            ModelSession next=null;
            try {
                ModelSession current=active.get(actor);
                if(current!=null&&!current.failed&&current.entry.id().equals(request.entry().id())) {
                    cancelPreparation(actor);
                    current.applySettings(settingsFor(current,request.settings()),false);return current;
                }
                nativeRuntime();next=new ModelSession(api,grip,request.entry());
                next.applySettings(settingsFor(next,request.settings()),false);
                if(request.remote())next.model.setPhysicsEnabled(false);
                stage(actor,next);
            } catch(Throwable e){if(next!=null)next.retire();switchFailures.add(actor);error(e);}
            refreshPoseOwners();
        }
        ModelSession next=preparing.get(actor);return next==null?active.get(actor):next;
    }
    static synchronized ModelSession createRoomPreview(String id) throws Exception {
        nativeRuntime();
        return new ModelSession(api,grip,entry(id));
    }

    private static void cancelPreparation(IsoGameCharacter actor) {
        ModelSession previous=preparing.remove(actor);if(previous!=null)previous.retire();refreshPoseOwners();
    }
    private static void stage(IsoGameCharacter actor,ModelSession next) {
        cancelPreparation(actor);preparing.put(actor,next);switchFailures.remove(actor);refreshPoseOwners();
    }
    static synchronized ModelSession completeCapture(IsoGameCharacter actor,ModelSession captured) {
        if(preparing.get(actor)!=captured)return active.get(actor);
        if(captured.failed||captured.retired){
            preparing.remove(actor);switchFailures.add(actor);captured.retire();
        }else if(captured.ready){
            preparing.remove(actor);ModelSession old=active.put(actor,captured);
            if(old!=null)old.retire();switchFailures.remove(actor);
        }
        refreshPoseOwners();
        return active.get(actor);
    }
    public static synchronized int modelCount() {
        try { configure();return entries.size(); } catch (Throwable e) { error(e);return 0; }
    }
    public static synchronized String modelLabel(int index) { return entries.get(index).label(); }
    public static synchronized String modelIdAt(int index) { return entries.get(index).id(); }
    public static synchronized boolean modelListed(int index) { return !hiddenEntries.contains(entries.get(index).id()); }
    public static synchronized boolean isEnabled(IsoGameCharacter actor) { return active.containsKey(actor); }
    public static synchronized boolean isReady(IsoGameCharacter actor) {
        ModelSession s=active.get(actor);return !preparing.containsKey(actor)&&!pending.containsKey(actor)&&s!=null && s.ready && !s.failed && !s.retired;
    }
    public static int modelSessionApi(){return 1;}
    public static synchronized boolean hasModelFailure(IsoGameCharacter actor) {
        ModelSession s=active.get(actor);return switchFailures.contains(actor)||s!=null&&(s.failed||s.retired);
    }
    public static synchronized String applySavedSettings(IsoGameCharacter actor,String value) {
        try {ModelSession s=required(actor);s.applySettings(settingsFor(s,value),false);return "";}
        catch(Throwable e){error(e);return lastError;}
    }
    public static synchronized String setModelPhysics(IsoGameCharacter actor,boolean enabled) {
        try {required(actor).model.setPhysicsEnabled(enabled);return "";}
        catch(Throwable e){error(e);return lastError;}
    }
    public static synchronized String resetModelPhysics(IsoGameCharacter actor) {
        try {
            ModelSession s=required(actor);
            if (!s.model.physicsEnabled()) return "当前人物未启用物理";
            s.model.resetPhysics();return "";
        } catch(Throwable e) {error(e);return lastError;}
    }
    public static synchronized String status() { return lastError; }
    public static synchronized String modelId(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?"":s.entry.id();}
    public static synchronized String appearanceJson(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?"":s.model.appearance().stateJson();}
    private static Entry entry(String id)throws Exception {
        configure();return entries.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("缺少模型 ID："+id));
    }
    private static JSONObject settings(String value) {
        if(value==null||value.length()>65536)throw new IllegalArgumentException("外观配置超过 64 KiB");
        JSONObject j=new JSONObject(value);j.getJSONObject("appearance");
        for(String key:j.keySet())if(!key.equals("appearance")&&!key.equals("weapon_tuning")&&!key.equals("chest_physics"))throw new IllegalArgumentException("不支持的同步字段："+key);
        return j;
    }
    private static JSONObject settingsFor(ModelSession session,String value) {
        return restoreMaterialSlots(settings(value),session.profile)
            .put("appearance_controls",session.profile.optJSONObject("appearance_controls"));
    }
    static JSONObject restoreMaterialSlots(JSONObject settings,JSONObject profile) {
        var previous=profile.optJSONArray("previous_material_slots");
        if(previous==null)return settings;
        var appearance=settings.getJSONObject("appearance");
        if(appearance.getJSONArray("material_visibility").length()!=previous.length())return settings;
        var defaults=profile.getJSONObject("appearance");
        for(String key:new String[]{"material_visibility","alpha_overrides","colour_overrides","double_sided","texture_overrides"}) {
            var saved=appearance.optJSONArray(key);
            if(saved==null)continue;
            if(saved.length()!=previous.length())throw new IllegalArgumentException("旧版外观材质数量不一致："+key);
            var current=new org.json.JSONArray(defaults.getJSONArray(key).toString());
            for(int old=0;old<previous.length();old++) {
                int slot=previous.getInt(old);
                if(slot>=0)current.put(slot,saved.get(old));
            }
            appearance.put(key,current);
        }
        var savedChoices=appearance.optJSONObject("choices");
        if(savedChoices!=null) {
            var choices=new JSONObject();
            for(Object value:profile.getJSONObject("appearance_controls").getJSONArray("groups")) {
                var group=(JSONObject)value;
                String id=group.getString("id"),saved=savedChoices.optString(id);
                for(Object option:group.getJSONArray("options"))
                    if(saved.equals(((JSONObject)option).getString("id")))choices.put(id,saved);
            }
            appearance.put("choices",choices);
        }
        return settings;
    }
    public static synchronized String settingsJson(IsoGameCharacter actor) {
        ModelSession s=active.get(actor);return s==null?"":new JSONObject().put("appearance",new JSONObject(s.model.appearance().stateJson()))
            .put("weapon_tuning",s.profile.optJSONObject("weapon_tuning")).put("chest_physics",s.model.chestSettings()).toString();
    }
    public static synchronized boolean hasChestPhysics(IsoGameCharacter actor){ModelSession s=active.get(actor);return s!=null&&s.model.hasChestPhysics();}
    public static synchronized double chestPhysicsSlider(IsoGameCharacter actor,int index){return required(actor).model.chestSlider(index);}
    public static synchronized String chestPhysicsStatus(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?"":s.model.chestError();}
    public static synchronized String setChestPhysicsSlider(IsoGameCharacter actor,int index,double value){try{required(actor).model.chestSlider(index,value);return "";}catch(Throwable e){error(e);return lastError;}}
    public static synchronized String resetChestPhysicsSettings(IsoGameCharacter actor){try{required(actor).model.resetChestSettings();return "";}catch(Throwable e){error(e);return lastError;}}
    public static synchronized String savedSettings(String id,String appearance) {
        try {JSONObject p=new JSONObject(Files.readString(entry(id).profile()));return new JSONObject().put("appearance",appearance==null||appearance.isBlank()?p.getJSONObject("appearance"):new JSONObject(appearance))
            .put("weapon_tuning",p.optJSONObject("weapon_tuning")).toString();}catch(Throwable e){error(e);return "";}
    }
    public static synchronized String networkSignature(String id) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(entry(id).profile())));}
        catch(Throwable e){error(e);return "";}
    }
    public static synchronized String networkAppearance(IsoGameCharacter actor) {
        ModelSession session=active.get(actor);
        if(session==null)return "";
        JSONObject state=new JSONObject(session.model.appearance().stateJson());
        return new JSONObject().put("choices",state.getJSONObject("choices"))
            .put("material_visibility",state.getJSONArray("material_visibility")).toString();
    }

    public static synchronized String queueRemoteAppearance(IsoPlayer actor,String id,String signature,String value) {
        if(actor==null||actor.isLocalPlayer())return "Invalid remote player";
        if(id.isEmpty()){disable(actor);return "";}
        try {
            Entry model=entry(id);
            if(!networkSignature(id).equals(signature))return "Character pack version mismatch";
            if(value.length()>8192)return "Remote appearance exceeds 8 KiB";
            JSONObject remote=networkSettings(model,value,"");
            cancelPreparation(actor);
            switchFailures.remove(actor);
            pending.put(actor,new Pending(model,remote.toString(),true));
            refreshPoseOwners();
            return "";
        }catch(Exception failure){return failure.toString();}
    }
    public static synchronized String queueOwnedAppearance(IsoPlayer actor,String id,String appearance,String localSettings) {
        if(actor==null||!actor.isLocalPlayer())return "Invalid local player";
        try {
            return queueSettings(actor,id,networkSettings(entry(id),appearance,localSettings).toString());
        }catch(Exception failure){return failure.toString();}
    }

    private static JSONObject networkSettings(Entry model,String value,String localSettings) throws Exception {
        if(value.length()>8192)throw new IllegalArgumentException("Network appearance exceeds 8 KiB");
        JSONObject profile=new JSONObject(Files.readString(model.profile()));
        JSONObject base=profile.getJSONObject("appearance");
        JSONObject result=localSettings.isBlank()
            ? new JSONObject().put("appearance",base).put("weapon_tuning",profile.optJSONObject("weapon_tuning"))
            : restoreMaterialSlots(settings(localSettings),profile);
        JSONObject supplied=new JSONObject(value);
        JSONArray visibility=supplied.getJSONArray("material_visibility");
        if(visibility.length()!=base.getJSONArray("material_visibility").length())
            throw new IllegalArgumentException("Material count mismatch");
        for(int i=0;i<visibility.length();i++)if(!(visibility.get(i) instanceof Boolean))
            throw new IllegalArgumentException("Invalid material visibility");
        // Remote players use local model assets and never receive texture paths or physics parameters.
        result.getJSONObject("appearance").put("material_visibility",visibility).put("choices",supplied.getJSONObject("choices"));
        return result;
    }

    public static synchronized String queueRestore(IsoGameCharacter actor,String id,String appearance) {
        return queueSettings(actor,id,savedSettings(id,appearance));
    }
    public static synchronized String queueSettings(IsoGameCharacter actor,String id,String value) {
        try {
            Entry e=entry(id);settings(value);
            cancelPreparation(actor);switchFailures.remove(actor);pending.put(actor,new Pending(e,value,false));refreshPoseOwners();return "";
        }catch(Throwable e){error(e);return lastError;}
    }
    private static ModelSession required(IsoGameCharacter actor){ModelSession s=active.get(actor);if(s==null)throw new IllegalStateException("请先启用人物");return s;}
    private static org.json.JSONArray controlGroups(ModelSession s){return new JSONObject(s.model.appearance().controlsJson()).getJSONArray("groups");}
    public static synchronized int groupCount(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?0:controlGroups(s).length();}
    public static synchronized String groupLabel(IsoGameCharacter actor,int group){JSONObject g=controlGroups(required(actor)).getJSONObject(group);return g.optString("label",g.getString("id"));}
    public static synchronized int selectedOption(IsoGameCharacter actor,int group) {
        ModelSession s=required(actor);JSONObject g=controlGroups(s).getJSONObject(group);
        String selected=new JSONObject(s.model.appearance().stateJson()).getJSONObject("choices").getString(g.getString("id"));
        var options=g.getJSONArray("options");for(int i=0;i<options.length();i++)if(selected.equals(options.getJSONObject(i).getString("id")))return i;return -1;
    }
    public static synchronized int optionCount(IsoGameCharacter actor,int group){return controlGroups(required(actor)).getJSONObject(group).getJSONArray("options").length();}
    public static synchronized String optionLabel(IsoGameCharacter actor,int group,int option){JSONObject o=controlGroups(required(actor)).getJSONObject(group).getJSONArray("options").getJSONObject(option);return o.optString("label",o.getString("id"));}
    public static synchronized String setChoice(IsoGameCharacter actor,int group,int option) {
        try {
            ModelSession s=required(actor);JSONObject g=controlGroups(s).getJSONObject(group),o=g.getJSONArray("options").getJSONObject(option);
            s.choose(g.getString("id"),o.getString("id"));return "";
        }catch(Throwable e){error(e);return lastError;}
    }
    public static synchronized int presetCount(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?0:s.profile.optJSONArray("material_presets")==null?0:s.profile.getJSONArray("material_presets").length();}
    public static synchronized String presetLabel(IsoGameCharacter actor,int index){return required(actor).profile.getJSONArray("material_presets").getJSONObject(index).getString("name");}
    public static synchronized String setPreset(IsoGameCharacter actor,int index) {
        try {
            ModelSession s=required(actor);JSONObject preset=s.profile.getJSONArray("material_presets").getJSONObject(index),state=new JSONObject(s.model.appearance().stateJson());
            for(String key:new String[]{"colour_overrides","alpha_overrides","double_sided","texture_overrides"})if(preset.has(key))state.put(key,preset.getJSONArray(key));
            s.applySettings(new JSONObject().put("appearance",state).put("appearance_controls",s.profile.optJSONObject("appearance_controls")),false);return "";
        }catch(Throwable e){error(e);return lastError;}
    }
    public static synchronized String reloadAppearance(IsoGameCharacter actor){try{required(actor).reload();return "";}catch(Throwable e){error(e);return lastError;}}
    public static synchronized int materialCount(IsoGameCharacter actor) {
        ModelSession s=active.get(actor);return s==null?0:s.profile.getJSONObject("appearance_catalog").getJSONArray("materials").length();
    }
    public static synchronized int materialIndex(IsoGameCharacter actor,int row) {
        JSONObject ui=required(actor).profile.optJSONObject("ui");var order=ui==null?null:ui.optJSONArray("material_order");return order==null?row:order.getInt(row);
    }
    public static synchronized String materialLabel(IsoGameCharacter actor,int index) {
        JSONObject m=required(actor).profile.getJSONObject("appearance_catalog").getJSONArray("materials").getJSONObject(index);return m.optString("label",m.getString("name"));
    }
    public static synchronized boolean materialVisible(IsoGameCharacter actor,int index) {
        return new JSONObject(required(actor).model.appearance().stateJson()).getJSONArray("material_visibility").getBoolean(index);
    }
    public static synchronized boolean materialAvailable(IsoGameCharacter actor,int index) {
        return required(actor).model.appearance().materialAvailable(index);
    }
    public static synchronized String setMaterialVisible(IsoGameCharacter actor,int index,boolean visible) {
        try {
            ModelSession s=required(actor);if(!materialAvailable(actor,index))throw new IllegalArgumentException("当前款式没有启用这个部件");
            JSONObject state=new JSONObject(s.model.appearance().stateJson());state.getJSONArray("material_visibility").put(index,visible);
            s.applySettings(new JSONObject().put("appearance",state).put("appearance_controls",new JSONObject(s.model.appearance().controlsJson())),false);return "";
        }catch(Throwable e){error(e);return lastError;}
    }
    public static synchronized String resetAppearance(IsoGameCharacter actor) {
        try {
            ModelSession s=required(actor);JSONObject original=new JSONObject(Files.readString(s.entry.profile()));
            s.applySettings(new JSONObject().put("appearance",original.getJSONObject("appearance")).put("appearance_controls",original.optJSONObject("appearance_controls")),false);return "";
        }catch(Throwable e){error(e);return lastError;}
    }
    public static synchronized int textureCount(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?0:s.textureCount();}
    public static synchronized long textureUploads(IsoGameCharacter actor){ModelSession s=active.get(actor);return s==null?0:s.textureUploads();}
    public static synchronized String enable(IsoGameCharacter actor,int index) {
        return enableWithSettings(actor,index,"");
    }
    public static synchronized String enableWithSettings(IsoGameCharacter actor,int index,String saved) {
        ModelSession next=null;
        try {
            if (actor==null || actor.getAnimationPlayer()==null) throw new IllegalStateException("角色动作尚未就绪");
            nativeRuntime();next=new ModelSession(api,grip,entries.get(index));
            if(saved!=null&&!saved.isBlank())next.applySettings(settingsFor(next,saved),false);
            pending.remove(actor);
            stage(actor,next);lastError="";
            System.out.println("[GFL2Exilium] enabled "+entries.get(index).label());return "";
        } catch (Throwable e) { if(next!=null)next.retire();error(e);return lastError; }
    }
    public static synchronized void disable(IsoGameCharacter actor) {
        pending.remove(actor);cancelPreparation(actor);switchFailures.remove(actor);ModelSession old=active.remove(actor);if(old!=null) old.retire();refreshPoseOwners();
    }
    public static synchronized void clear() {
        for(ModelSession session:preparing.values())session.retire();preparing.clear();
        for(ModelSession session:active.values()) session.retire();active.clear();pending.clear();switchFailures.clear();refreshPoseOwners();
    }
    static void error(Throwable e) {
        lastError=e.toString();System.err.println("[GFL2Exilium] "+lastError);e.printStackTrace();
    }
}
