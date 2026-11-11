package aimoh.pmxpreview;

import aimoh.motion.OzzGrip;
import aimoh.motion.PmxDrawMesh;
import aimoh.motion.PmxModel;
import aimoh.motion.PmxShading;
import aimoh.motion.PzPoseCapture;
import aimoh.motion.ResourceCleanup;
import aimoh.motion.WorldPoseRetargeter.GripAdjustment;
import com.shiroha.mmdskin.NativeFunc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import org.json.JSONObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import viewpoint.core.Frame;
import viewpoint.render.Retirement;
import viewpoint.render.ModelDraws;
import zombie.characters.IsoPlayer;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.textures.Texture;

final class ModelSession {
    final PmxModel model;
    final IdleHandPose idleHand;
    final PmxShading shading;
    final NativeFunc api;
    final OzzGrip grip;
    final JSONObject profile;
    final float scale;
    final String[] sourceNames;
    final float[] reference;
    final ByteBuffer positions,normals;
    volatile PmxDrawMesh mesh,preparedMesh;
    volatile boolean ready,failed,retired;
    private TextureFactory textures;
    private boolean queued;
    private long lastFrame=Long.MIN_VALUE,lastNanos,lastWallNanos,lastSlowFrame=Long.MIN_VALUE;
    private long lastMotionRevision;
    private PmxDrawMesh.FrameData frozen;
    PmxDrawMesh.FrameData previousFrozen,olderFrozen;
    final PmxPresentation presentation=new PmxPresentation();
    private float[] sourcePose;
    private GripAdjustment gripAdjustment;
    private boolean preserveAimWeapon;
    private int[] sourceIndices;
    private SkinningData sourceSkin;
    private final boolean[] required;
    private final int[] sourceIds,targetIds;
    final int sourceHead,targetHead;
    private final int sourcePelvis,targetPelvis;
    record Materials(long version,long epoch,float[] factors,float[] shadow,String[] paths,boolean[] doubleSided) {}
    Materials materials;
    final Main.Entry entry;
    private long appearanceRevision=Long.MIN_VALUE,materialVersion,imageEpoch;
    private boolean hiddenHead;
    private static final class DrawHistory { ModelDraws draws;int index;long frame; }
    private final IdentityHashMap<ModelMesh,DrawHistory> drawHistory=new IdentityHashMap<>();

    ModelSession(NativeFunc api,OzzGrip grip,Main.Entry entry) throws Exception {
        this.api=api;this.grip=grip;this.entry=entry;
        profile=new JSONObject(Files.readString(entry.profile()));shading=PmxShading.load(entry.model(),profile);scale=(float)profile.getDouble("unit_scale");
        var names=profile.getJSONArray("source_bone_names");sourceNames=new String[names.length()];
        for(int i=0;i<sourceNames.length;i++)sourceNames[i]=names.getString(i);
        var ref=profile.getJSONArray("source_reference");reference=new float[ref.length()];for(int i=0;i<reference.length;i++)reference[i]=(float)ref.getDouble(i);
        required=new boolean[sourceNames.length];
        for(String key:new String[]{"source_ids","source_arms","palm_source_hands","palm_source_knuckles"}) {
            var a=profile.getJSONArray(key);for(int i=0;i<a.length();i++)required[a.getInt(i)]=true;
        }
        required[profile.getInt("source_root")]=true;
        var s=profile.getJSONArray("source_ids");var t=profile.getJSONArray("target_ids");sourceIds=new int[s.length()];targetIds=new int[t.length()];
        for(int i=0;i<sourceIds.length;i++){sourceIds[i]=s.getInt(i);targetIds[i]=t.getInt(i);}
        int head=-1,headTarget=-1,pelvis=-1,pelvisTarget=-1;
        for(int i=0;i<sourceIds.length;i++){
            String name=sourceNames[sourceIds[i]];
            if(head<0&&"Bip01_Head".equals(name)){head=sourceIds[i];headTarget=targetIds[i];}
            else if(pelvis<0&&"Bip01_Pelvis".equals(name)){pelvis=sourceIds[i];pelvisTarget=targetIds[i];}
        }
        sourceHead=head;targetHead=headTarget;sourcePelvis=pelvis;targetPelvis=pelvisTarget;
        model=PmxModel.load(api,entry.model(),entry.profile());
        try {
        IdleHandPose candidate;
        try { candidate=new IdleHandPose(api,model,profile); }
        catch(RuntimeException e) { candidate=null;System.out.println("[AimohPMXPreview] Idle hand unavailable: "+e); }
        idleHand=candidate;
        positions=ByteBuffer.allocateDirect(Math.toIntExact(profile.getLong("vertices")*12)).order(ByteOrder.nativeOrder());
        normals=ByteBuffer.allocateDirect(positions.capacity()).order(ByteOrder.nativeOrder());
        }catch(RuntimeException|Error e){model.close();throw e;}
    }
    private float[] pose(AnimationPlayer player) {
        var skin=player.getSkinningData();float[] actual=PzPoseCapture.capture(player,skin.skeletonHierarchy.size());
        if(sourceSkin!=skin) {
            sourceIndices=new int[sourceNames.length];
            for(int i=0;i<sourceNames.length;i++) {
                Integer source=skin.boneIndices.get(sourceNames[i]);sourceIndices[i]=source==null?-1:source;
                if(source==null&&required[i])throw new IllegalArgumentException("游戏骨架缺少："+sourceNames[i]);
            }
            sourceSkin=skin;
        }
        float[] result=reference.clone();
        for(int i=0;i<sourceNames.length;i++)if(sourceIndices[i]>=0)System.arraycopy(actual,sourceIndices[i]*16,result,i*16,16);
        return result;
    }
    synchronized PmxDrawMesh.FrameData update(Frame frame,AnimationPlayer player,boolean holding,float x,float y,float z,boolean hideHead) {
        return update(frame,player,holding,x,y,z,hideHead,false);
    }
    synchronized PmxDrawMesh.FrameData update(Frame frame,AnimationPlayer player,boolean holding,float x,float y,float z,boolean hideHead,boolean idleEligible) {
        return update(frame,player,holding,x,y,z,hideHead,idleEligible,false);
    }
    synchronized PmxDrawMesh.FrameData update(Frame frame,AnimationPlayer player,boolean holding,float x,float y,float z,boolean hideHead,boolean idleEligible,boolean firstPerson) {
        return update(frame,player,holding,x,y,z,hideHead,idleEligible,firstPerson,null);
    }
    synchronized PmxDrawMesh.FrameData update(Frame frame,AnimationPlayer player,boolean holding,float x,float y,float z,boolean hideHead,
            boolean idleEligible,boolean firstPerson,GripAdjustment adjustment) {
        if(lastFrame==frame.number)return frozen;
        drawHistory.values().removeIf(history->history.frame<frame.number-1);
        sourcePose=pose(player);
        long wallNanos=System.nanoTime(),capturedNanos=DrawAccess.frameNanos(frame);
        float seconds=lastNanos==0?0:Math.clamp((capturedNanos-lastNanos)*1e-9f,0,.08f);
        // The target placement is R_y(-angle); the native inertia API negates its yaw input.
        model.retargeter().setModelMotion(-x*scale/1.5f,z*2.4494896f*scale/1.5f,-y*scale/1.5f,player.getRenderedAngle());
        if(model.motionActive()&&player.getIsoGameCharacter() instanceof IsoPlayer actor&&!ReadyRoomPreview.motionAllowed(actor))model.stopMotion();
        long revision=model.motionRevision();boolean continuous=revision==lastMotionRevision&&frame.number==lastFrame+1;
        float[] poseForModel=idleHand==null||model.motionActive()?sourcePose:idleHand.apply(sourcePose,sourceIndices,seconds,idleEligible);
        preserveAimWeapon=holding&&firstPerson&&EfzAimCompatibility.keepsWeapon(player.getIsoGameCharacter());
        gripAdjustment=preserveAimWeapon&&adjustment!=null?adjustment.wristsOnly():adjustment;
        // Fit both PMX palms to the posed source hands in the same head frame as the sight.
        model.apply(poseForModel,holding?grip:null,seconds,firstPerson?sourceHead:-1,firstPerson?targetHead:-1,gripAdjustment);
        if(materials==null||appearanceRevision!=model.appearance().revision()||materials.epoch()!=imageEpoch) {
            String[] paths=model.appearance().readTextures();
            for(int i=0;i<paths.length;i++)paths[i]=resolveTexture(paths[i]);
            materials=new Materials(++materialVersion,imageEpoch,model.appearance().readTextureFactors(),model.appearance().readMaterials(false),paths,model.appearance().readBothFaces());
            appearanceRevision=model.appearance().revision();
        }
        if(hiddenHead!=hideHead){api.SetFirstPersonMode(model.handle(),hideHead);hiddenHead=hideHead;}
        api.CopyDataToByteBuffer(positions,api.GetPoss(model.handle()),positions.capacity());
        api.CopyDataToByteBuffer(normals,api.GetNormals(model.handle()),normals.capacity());
        float[] p=new float[positions.capacity()/4],n=new float[p.length];
        positions.asFloatBuffer().get(p);normals.asFloatBuffer().get(n);
        model.deformChest(p,n);
        float[] finalMaterials=model.appearance().readMaterials();
        PmxDrawMesh.FrameData next=new PmxDrawMesh.FrameData(p,n,finalMaterials,shading==null?null:shading.freeze(api,model.handle(),finalMaterials,p,materials.paths()));
        long cpuNanos=System.nanoTime()-wallNanos,wallGap=lastWallNanos==0?0:wallNanos-lastWallNanos;
        if((cpuNanos>50_000_000L||wallGap>100_000_000L)&&(lastSlowFrame==Long.MIN_VALUE||frame.number-lastSlowFrame>=300)){
            lastSlowFrame=frame.number;
            System.out.println("[AimohPMXPreview] Slow model "+entry.id()+" "+model.stepTiming().put("capture_cpu_ms",cpuNanos/1e6).put("capture_gap_ms",wallGap/1e6).put("simulation_dt",seconds));
        }
        olderFrozen=continuous?previousFrozen:null;previousFrozen=continuous?frozen:null;lastMotionRevision=revision;
        frozen=next;lastFrame=frame.number;lastNanos=capturedNanos;lastWallNanos=wallNanos;
        prepareMesh(frozen);
        return frozen;
    }
    synchronized void prepareMesh(PmxDrawMesh.FrameData frame){
        preparedMesh=mesh==null?null:mesh.readyMesh();ready=preparedMesh!=null;
        if(!ready&&!queued){queued=true;RenderThread.queueInvokeOnRenderContext(()->initialize(frame));}
    }
    private synchronized void initialize(PmxDrawMesh.FrameData frame) {
        try {
            if(retired||failed)return;
            if(textures==null)textures=new TextureFactory();Texture[] material=prepareMaterials(materials);
            boolean first=mesh==null;if(first)mesh=new PmxDrawMesh(api,model.handle(),material,model.appearance(),shading);
            mesh.upload(frame);preparedMesh=mesh.readyMesh();ready=preparedMesh!=null;
            if(first)
            System.out.println("[AimohPMXPreview] GPU ready "+frame.positions().length/3+" vertices, "+material.length+" materials; physics "+model.stepTiming());
        } catch(Throwable e) { fail(e); }finally{queued=false;}
    }
    Texture[] prepareMaterials(Materials state)throws Exception {Texture[] result=textures.prepare(state.paths(),state.factors(),state.version(),state.epoch(),shading);if(mesh!=null)mesh.updateOutlineTextures(result);return result;}
    private String resolveTexture(String file) {
        if(file.isBlank())return "";
        Path root=entry.model().toAbsolutePath().getParent(),path=Path.of(file);path=(path.isAbsolute()?path:root.resolve(path)).normalize().toAbsolutePath();
        if(!path.startsWith(root)||!Files.exists(path))throw new IllegalArgumentException("贴图引用缺失或越出模型目录："+file);return path.toString();
    }
    private void validateTextures(JSONObject settings) {
        var state=settings.getJSONObject("appearance").optJSONArray("texture_overrides");
        if(state!=null)for(int i=0;i<state.length();i++)if(!state.isNull(i))resolveTexture(state.getString(i));
    }
    synchronized void applySettings(JSONObject settings,boolean reloadImages) {
        for(String key:new String[]{"appearance_controls","weapon_tuning"})if(settings.has(key))settings.getJSONObject(key);
        if(settings.has("material_presets"))settings.getJSONArray("material_presets");
        validateTextures(settings);model.applySettings(settings.toString());
        if(settings.has("hand_relax_strength")&&idleHand!=null) {
            idleHand.setStrength(settings.optDouble("hand_relax_strength",0));profile.put("hand_relax_strength",settings.optDouble("hand_relax_strength",0));
        }
        profile.put("appearance",new JSONObject(model.appearance().stateJson()));
        if(settings.has("appearance_controls"))profile.put("appearance_controls",settings.getJSONObject("appearance_controls"));
        if(settings.has("weapon_tuning"))profile.put("weapon_tuning",settings.getJSONObject("weapon_tuning"));
        if(settings.has("material_presets"))profile.put("material_presets",settings.getJSONArray("material_presets"));
        if(reloadImages)imageEpoch++;
    }
    synchronized void choose(String group,String option) {
        model.appearance().choose(group,option);profile.put("appearance",new JSONObject(model.appearance().stateJson()));
    }
    synchronized void reload()throws Exception {
        JSONObject next=new JSONObject(Files.readString(entry.profile()));
        for(String key:new String[]{"pmx_sha256","calibration_revision","neutral_qps","source_reference","source_ids","target_ids","parents","unit_scale"})
            if(!new JSONObject().put("value",profile.get(key)).similar(new JSONObject().put("value",next.get(key))))
                throw new IllegalArgumentException("模型或标定已变化，请重新启用人物；纯外观更新不需要重新启用");
        applySettings(next,true);
    }
    int textureCount(){return textures==null?0:textures.liveTextures;}
    long textureUploads(){return textures==null?0:textures.uploads;}
    synchronized boolean retargetsAttachment(DrawAccess.Gear gear){return !preserveAimWeapon||gear.side!=1;}
    synchronized Matrix4f attachment(DrawAccess.Gear gear,Matrix4f actual,AnimationPlayer player) {
        if(gear.side==1&&gripAdjustment!=null){
            Matrix4f locked=gripAdjustment.placement(actual);
            if(locked!=null)return locked;
        }
        if(gear.side>=0)return new Matrix4f().set(model.remapHeldItem(sourcePose,gear.side==0,actual.get(new float[16]),gear.key));
        String name=gear.root.parentBoneName;var skin=player.getSkinningData();
        Integer bone=name==null?null:skin.boneIndices.get(name);
        while(bone!=null&&bone>=0) {
            for(int i=0;i<sourceIds.length;i++)if(sourceIndices[sourceIds[i]]==bone)
                return new Matrix4f().set(model.retargeter().remapBodyAttachment(sourcePose,sourceIds[i],targetIds[i],actual.get(new float[16])));
            bone=skin.skeletonHierarchy.get(bone);
        }
        return new Matrix4f().scaling(scale,scale,-scale).mul(actual);
    }
    synchronized Matrix4f firstPersonHeadPlacement(Matrix4f source,Matrix4f target) {
        return anchorPlacement(source,target,sourceHead,targetHead);
    }
    synchronized Matrix4f vehicleSeatPlacement(Matrix4f source,Matrix4f target) {
        return anchorPlacement(source,target,sourcePelvis,targetPelvis);
    }
    private Matrix4f anchorPlacement(Matrix4f source,Matrix4f target,int sourceBone,int targetBone) {
        if(sourceBone<0||targetBone<0)return target;
        Matrix4f anchor=new Matrix4f().set(sourcePose,sourceBone*16);
        Matrix4f mapped=new Matrix4f().set(model.retargeter().remapBodyAttachment(sourcePose,sourceBone,targetBone,anchor.get(new float[16])));
        Vector3f delta=source.transformPosition(anchor.getTranslation(new Vector3f()))
            .sub(target.transformPosition(mapped.getTranslation(new Vector3f())));
        return new Matrix4f(target).m30(target.m30()+delta.x).m31(target.m31()+delta.y).m32(target.m32()+delta.z);
    }
    void fail(Throwable e) { if(!failed){failed=true;ready=false;Main.error(e);} }
    synchronized boolean followDraw(ModelDraws draws,int index,long frame) {
        ModelMesh mesh=DrawAccess.mesh(draws,index);
        DrawHistory old=drawHistory.get(mesh);
        if(old==null){old=new DrawHistory();drawHistory.put(mesh,old);}
        if(old.frame==frame&&old.draws==draws&&old.index==index)return false;
        // Reuse the host's motion/interpolation contract while keeping immutable upload command keys.
        boolean continuous=old.draws!=null&&old.frame==frame-1&&old.draws!=draws&&old.index<old.draws.count()&&DrawAccess.mesh(old.draws,old.index)==mesh;
        if(continuous)draws.motion(index,old.draws,old.index,0,false);
        old.draws=draws;old.index=index;old.frame=frame;
        return continuous;
    }
    synchronized void retire() {
        if(retired)return;retired=true;ready=false;preparedMesh=null;
        drawHistory.clear();
        Retirement.retire(()->{
            try{ResourceCleanup.close(mesh,textures,model);}
            catch(Throwable e){Main.error(e);}
            finally{mesh=null;textures=null;frozen=previousFrozen=olderFrozen=null;presentation.clear();}
        });
    }
}
