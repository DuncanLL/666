package aimoh.pmxpreview;

import aimoh.motion.OzzGrip;
import aimoh.motion.GpuDeformation;
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
import org.json.JSONObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.characters.IsoPlayer;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.model.ModelInstance;
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
    volatile ViewpointModel viewpointModel;
    private ReadyRoomPreview.Mesh plainMesh;
    private GpuDeformation gpu;
    private boolean gpuDisabled;
    volatile boolean ready,failed,retired;
    private TextureFactory plainTextures;
    private boolean viewpointQueued,plainQueued;
    private long lastFrame=Long.MIN_VALUE,lastNanos,lastWallNanos,lastSlowFrame=Long.MIN_VALUE;
    private long lastMotionRevision;
    private PmxDrawMesh.FrameData frozen;
    private boolean frozenViewpoint;
    PmxDrawMesh.FrameData previousFrozen,olderFrozen;
    private PmxDrawMesh.FrameData fullFrame;
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
    record Snapshot(PmxDrawMesh.FrameData frame, Materials materials) {}
    Materials materials;
    final Main.Entry entry;
    private long appearanceRevision=Long.MIN_VALUE,materialVersion,imageEpoch;
    private boolean hiddenHead;

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
    synchronized PmxDrawMesh.FrameData update(long frame,long capturedNanos,AnimationPlayer player,boolean holding,float x,float y,float z,
            boolean hideHead,boolean idleEligible,boolean firstPerson,GripAdjustment adjustment,boolean viewpoint) {
        if(lastFrame==frame) {
            if(viewpoint&&fullFrame.gpuPose()!=null){
                if(!model.skinCompletedPose())throw new IllegalStateException("Completed CPU fallback pose unavailable");
                fullFrame=cpuFrame(model.appearance().readMaterials(false),false);
                frozen=fullFrame;frozenViewpoint=false;previousFrozen=olderFrozen=null;
            }
            if(viewpoint&&(!frozenViewpoint||hiddenHead!=hideHead)){
                if(hiddenHead!=hideHead){api.SetFirstPersonMode(model.handle(),hideHead);hiddenHead=hideHead;}
                float[] visible=model.appearance().readMaterials();
                frozen=new PmxDrawMesh.FrameData(fullFrame.positions(),fullFrame.normals(),visible,
                        shading==null?null:shading.freeze(api,model.handle(),visible,fullFrame.positions(),materials.paths()));
                frozenViewpoint=true;
            }
            prepareMesh(viewpoint ? frozen : fullFrame,viewpoint);
            return viewpoint ? frozen : fullFrame;
        }
        sourcePose=pose(player);
        long wallNanos=System.nanoTime();
        float seconds=lastNanos==0?0:Math.clamp((capturedNanos-lastNanos)*1e-9f,0,.08f);
        // The target placement is R_y(-angle); the native inertia API negates its yaw input.
        model.retargeter().setModelMotion(-x*scale/1.5f,z*2.4494896f*scale/1.5f,-y*scale/1.5f,player.getRenderedAngle());
        if(model.motionActive()&&player.getIsoGameCharacter() instanceof IsoPlayer actor&&!ReadyRoomPreview.motionAllowed(actor))model.stopMotion();
        long revision=model.motionRevision();boolean continuous=revision==lastMotionRevision&&frame==lastFrame+1;
        float[] poseForModel=idleHand==null||model.motionActive()?sourcePose:idleHand.apply(sourcePose,sourceIndices,seconds,idleEligible);
        preserveAimWeapon=holding&&firstPerson&&EfzAimCompatibility.keepsWeapon(player.getIsoGameCharacter());
        gripAdjustment=preserveAimWeapon&&adjustment!=null?adjustment.wristsOnly():adjustment;
        // Fit both PMX palms to the posed source hands in the same head frame as the sight.
        boolean useGpu=!viewpoint&&gpu!=null&&!gpuDisabled;
        if(useGpu)model.applyPose(poseForModel,holding?grip:null,seconds,-1,-1,gripAdjustment);
        else model.apply(poseForModel,holding?grip:null,seconds,firstPerson?sourceHead:-1,firstPerson?targetHead:-1,gripAdjustment);
        if(materials==null||appearanceRevision!=model.appearance().revision()||materials.epoch()!=imageEpoch) {
            String[] paths=model.appearance().readTextures();
            for(int i=0;i<paths.length;i++)paths[i]=resolveTexture(paths[i]);
            materials=new Materials(++materialVersion,imageEpoch,model.appearance().readTextureFactors(),model.appearance().readMaterials(false),paths,model.appearance().readBothFaces());
            appearanceRevision=model.appearance().revision();
        }
        if(hiddenHead!=hideHead){api.SetFirstPersonMode(model.handle(),hideHead);hiddenHead=hideHead;}
        float[] finalMaterials=model.appearance().readMaterials();
        PmxDrawMesh.FrameData next=null;
        if(useGpu)try { next=new PmxDrawMesh.FrameData(finalMaterials,gpu.capture()); }
        catch(RuntimeException|LinkageError failure) {
            disableGpu(failure);
            if(!model.skinCompletedPose())throw new IllegalStateException("GPU pose fallback failed",failure);
        }
        if(next==null)next=cpuFrame(finalMaterials,viewpoint);
        fullFrame=hideHead?new PmxDrawMesh.FrameData(next.positions(),next.normals(),model.appearance().readMaterials(false)):next;
        long cpuNanos=System.nanoTime()-wallNanos,wallGap=lastWallNanos==0?0:wallNanos-lastWallNanos;
        if((cpuNanos>50_000_000L||wallGap>100_000_000L)&&(lastSlowFrame==Long.MIN_VALUE||frame-lastSlowFrame>=300)){
            lastSlowFrame=frame;
            System.out.println("[AimohPMXPreview] Slow model "+entry.id()+" "+model.stepTiming().put("capture_cpu_ms",cpuNanos/1e6).put("capture_gap_ms",wallGap/1e6).put("simulation_dt",seconds));
        }
        boolean history=continuous&&frozen!=null&&frozen.gpuPose()==null&&next.gpuPose()==null;
        olderFrozen=history?previousFrozen:null;previousFrozen=history?frozen:null;lastMotionRevision=revision;
        frozen=next;frozenViewpoint=viewpoint;lastFrame=frame;lastNanos=capturedNanos;lastWallNanos=wallNanos;
        prepareMesh(viewpoint?frozen:fullFrame,viewpoint);
        return viewpoint?frozen:fullFrame;
    }
    private PmxDrawMesh.FrameData cpuFrame(float[] finalMaterials,boolean viewpoint) {
        api.CopyDataToByteBuffer(positions,api.GetPoss(model.handle()),positions.capacity());
        api.CopyDataToByteBuffer(normals,api.GetNormals(model.handle()),normals.capacity());
        float[] p=new float[positions.capacity()/4],n=new float[p.length];
        positions.asFloatBuffer().get(p);normals.asFloatBuffer().get(n);model.deformChest(p,n);
        return new PmxDrawMesh.FrameData(p,n,finalMaterials,
            !viewpoint||shading==null?null:shading.freeze(api,model.handle(),finalMaterials,p,materials.paths()));
    }

    synchronized void disableGpu(Throwable failure) {
        if(!gpuDisabled)System.err.println("[R116] GPU deformation fallback for "+entry.id()+": "+failure);
        gpuDisabled=true;
    }
    synchronized void prepareMesh(PmxDrawMesh.FrameData frame,boolean viewpoint) {
        if(viewpoint) {
            if(viewpointModel==null)viewpointModel=new ViewpointModel(this);
            ready=viewpointModel.ready();
            if(!ready&&!viewpointQueued){viewpointQueued=true;RenderThread.queueInvokeOnRenderContext(()->initialize(frame,true));}
        } else {
            ready=plainMesh!=null;
            if((!ready||gpu==null&&!gpuDisabled)&&!plainQueued){
                plainQueued=true;RenderThread.queueInvokeOnRenderContext(()->initialize(frame,false));
            }
        }
    }
    private synchronized void initialize(PmxDrawMesh.FrameData frame,boolean viewpoint) {
        try {
            if(retired||failed)return;
            if(viewpoint){viewpointModel.upload(frame,materials);ready=viewpointModel.ready();}
            else {
                preparePlainMaterials(materials);plainMesh(frame);ready=true;
                if(gpu==null&&!gpuDisabled) {
                    try {gpu=GpuDeformation.create(api,model);gpuDisabled=gpu==null;}
                    catch(RuntimeException|LinkageError failure){disableGpu(failure);}
                }
            }
        } catch(Throwable e) { fail(e); }
        finally {if(viewpoint)viewpointQueued=false;else plainQueued=false;}
    }
    synchronized Snapshot snapshot() {
        return fullFrame==null||retired||failed?null:new Snapshot(fullFrame,materials);
    }
    synchronized ReadyRoomPreview.Mesh plainMesh(PmxDrawMesh.FrameData frame) {
        if(plainMesh==null)plainMesh=new ReadyRoomPreview.Mesh(this,frame);
        return plainMesh;
    }
    Texture[] preparePlainMaterials(Materials state)throws Exception {
        if(plainTextures==null)plainTextures=new TextureFactory(false);
        return plainTextures.prepare(state.paths(),state.factors(),state.version(),state.epoch());
    }
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
    int textureCount(){return (plainTextures==null?0:plainTextures.liveTextures)+(viewpointModel==null?0:viewpointModel.textureCount());}
    long textureUploads(){return (plainTextures==null?0:plainTextures.uploads)+(viewpointModel==null?0:viewpointModel.textureUploads());}
    synchronized boolean retargetsAttachment(int side){return !preserveAimWeapon||side!=1;}
    synchronized Matrix4f attachment(ModelInstance root,int side,String key,Matrix4f actual,AnimationPlayer player) {
        if(gripAdjustment!=null&&side==gripAdjustment.heldSide()){
            Matrix4f locked=gripAdjustment.placement(actual);
            if(locked!=null)return locked;
        }
        if(side>=0)return new Matrix4f().set(model.remapHeldItem(sourcePose,side==0,actual.get(new float[16]),key));
        String name=root.parentBoneName;var skin=player.getSkinningData();
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
    synchronized void retire() {
        if(retired)return;retired=true;ready=false;
        ViewpointMode.retire(()->{
            try{ResourceCleanup.close(viewpointModel,plainMesh,plainTextures,gpu,model);}
            catch(Throwable e){Main.error(e);}
            finally{viewpointModel=null;plainMesh=null;plainTextures=null;gpu=null;frozen=fullFrame=previousFrozen=olderFrozen=null;}
        });
    }
}
