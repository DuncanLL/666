package gfl2.runtime;

import gfl2.motion.PmxDrawMesh;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import viewpoint.core.Frame;
import viewpoint.render.ModelDraws;
import viewpoint.render.SceneData;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCamera;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;

public final class Bridge {
    private static long preparation;
    private static ModelDraws preparedDraws;
    private static final ArrayList<Frozen> preparedHeld=new ArrayList<>();
    private static final ThreadLocal<Integer> start=new ThreadLocal<>();
    public static void begin(Frame frame) { start.set(frame.scene.models.count()); }
    public static void end(Frame frame,ModelSlotRenderData slot,IsoGameCharacter actor,boolean hideHead,boolean captured) {
        Integer first=start.get();start.remove();if(!captured||first==null)return;
        ModelSession session=Main.sessionForCapture(actor);if(session==null)return;
        ModelDraws draws=frame.scene.models;int originalEnd=draws.count();
        Frozen draw=null;
        try {
            boolean firstPerson=hideHead&&!slot.inVehicle;
            boolean holding=actor.getPrimaryHandItem()!=null||actor.getSecondaryHandItem()!=null;
            boolean idle=IdleHandPose.eligible(actor,holding);
            var equipment=DrawAccess.equipment(actor,slot.modelSlot,draws,first,originalEnd);
            int calibrationSide=slot.inVehicle?-1:GripCalibration.heldSide(actor);
            int body=-1, gun=-1;
            for(int n=first;n<originalEnd;n++){
                ModelInstance instance=DrawAccess.instance(draws,n);
                DrawAccess.Gear gear=equipment.get(instance);
                if(body<0&&gear==null)body=n;
                if(calibrationSide>=0&&gear!=null&&gear.side==calibrationSide&&instance.model!=null&&instance.model.isStatic
                    &&(gun<0||instance==gear.root))gun=n;
            }
            Matrix4f sourcePlacement=body<0?null:DrawAccess.matrix(draws,body);
            var sourceItems=new IdentityHashMap<ModelInstance,ModelInstanceRenderData>();
            Matrix4f bodyInverse=null;
            if(!equipment.isEmpty())for(var capturedPart:slot.modelData){
                var instance=capturedPart.modelInstance;
                if(instance==slot.modelSlot.model)bodyInverse=new Matrix4f(capturedPart.xfrm).transpose().invert();
                if(equipment.containsKey(instance))sourceItems.put(instance,capturedPart);
            }
            Matrix4f inverse=bodyInverse;
            Matrix4f weapon=gun<0?null:heldSource(sourceItems.get(DrawAccess.instance(draws,gun)),inverse);
            PmxDrawMesh.FrameData data=null;
            long gameFrame=IsoCamera.frameState.frameCount;
            long captureNanos=DrawAccess.frameNanos(frame);
            var pose=slot.animPlayer==null?actor.getAnimationPlayer():slot.animPlayer;
            try {if(!session.failed)data=session.update(gameFrame,captureNanos,pose,holding,
                actor.getX(),actor.getY(),actor.getZ(),hideHead,idle,firstPerson,
                GripCalibration.request(actor,session.entry.id(),weapon,slot.inVehicle?null:pose),true);}catch(Throwable e){session.fail(e);}
            ModelSession selected=Main.completeCapture(actor,session);
            if(selected!=session){
                session=selected;if(session==null||session.failed)return;
                data=session.update(gameFrame,captureNanos,pose,holding,actor.getX(),actor.getY(),actor.getZ(),hideHead,idle,firstPerson,
                    GripCalibration.request(actor,session.entry.id(),weapon,slot.inVehicle?null:pose),true);
            }
            if(session.failed||!session.ready)return;
            ViewpointModel backend=session.viewpointModel;
            PmxDrawMesh prepared=backend==null?null:backend.prepared;if(prepared==null)return;
            if(body<0)return;
            Matrix4f targetPlacement=new Matrix4f(sourcePlacement).scale(1,1,-1).scale(1/session.scale);
            if(slot.inVehicle)targetPlacement=session.vehicleSeatPlacement(sourcePlacement,targetPlacement);
            else if(firstPerson)targetPlacement=session.firstPersonHeadPlacement(sourcePlacement,targetPlacement);
            draw=new Frozen(session,prepared,data,draws,first,originalEnd);
            backend.retainHeldFrames(data,draw.previous,draw.older);
            Frozen command=draw;
            int added=prepared.submitPreparedFrozen(draws,targetPlacement,data,material->new Part(command,material),draw.materials.shadow(),draw.materials.doubleSided());
            if(added==0)return;
            draw.customEnd=draws.count();
            boolean continuous=false;
            for(int n=originalEnd;n<draw.customEnd;n++) {
                DrawAccess.style(draws,body,n);continuous|=backend.followDraw(draws,n,frame.number);
                if(draw.low!=null)draws.bounds(n,targetPlacement,draw.low,draw.high,1);
            }
            for(int n=first;n<originalEnd;n++) {
                ModelInstance instance=DrawAccess.instance(draws,n);DrawAccess.Gear gear=equipment.get(instance);
                if(gear==null)DrawAccess.flags(draws,n,0);
                else if(instance.model.isStatic&&inverse!=null&&session.retargetsAttachment(gear.side)) {
                    var source=sourceItems.get(instance);
                    if(source==null)continue;
                    var mesh=DrawAccess.mesh(draws,n);
                    HeldDraw held=backend.captureHeld(draw,n,instance,gear,mesh,
                        () -> selected.attachment(gear.root,gear.side,gear.key,heldSource(source,inverse),pose));
                    Matrix4f placement=new Matrix4f(targetPlacement).mul(held.current());
                    DrawAccess.matrix(draws,n,placement);
                    draws.bounds(n,placement,mesh.minXyz,mesh.maxXyz,1);
                    DrawAccess.ownPlacement(draws,n,originalEnd,continuous);
                    draw.addHeld(held);
                }
            }
        } catch(Throwable e) { if(draw!=null){draw.customEnd=draws.count();draw.restore();}session.fail(e); }
    }
    static Matrix4f heldSource(ModelInstanceRenderData part,Matrix4f inverseBody) {
        // The game slot contains the bone and script mounts before Viewpoint's render placement.
        return part==null||inverseBody==null?null:new Matrix4f(inverseBody).mul(new Matrix4f(part.xfrm).transpose());
    }
    public static void prepare(SceneData scene) {
        prepare(scene,1);
        presentationHeaders(scene.models);
    }
    public static void prepare(SceneData scene,float phase) {
        long serial=++preparation;ModelDraws draws=scene.models;
        preparedDraws=draws;preparedHeld.clear();
        for(int n=0;n<draws.count();n++)if(draws.key(n) instanceof Part part){
            Frozen frame=part.frame;
            if(frame.preparedSerial!=serial){
                frame.upload(serial,phase,scene.cameraCut);
                if(frame.held!=null&&frame.timing!=null)preparedHeld.add(frame);
            }
            if(!frame.session.failed&&!frame.session.retired&&frame.boundTextures!=null)
                DrawAccess.texture(draws,n,frame.boundTextures[part.material]);
        }
    }
    public static void presentHeld(ModelDraws captured,ModelDraws drawn) {
        if(captured!=preparedDraws)return;
        preparedDraws=null;
        for(Frozen frame:preparedHeld)if(!frame.session.failed&&!frame.session.retired)frame.presentHeld(drawn);
        preparedHeld.clear();
    }
    public static void presentationHeaders(ModelDraws draws) {
        for(int n=0;n<draws.count();n++)if(draws.key(n) instanceof Part part){
            Frozen f=part.frame;
            if(f.renderedHeader!=null&&(f.headerSerial!=preparation||f.headerDraws!=draws)){
                DrawAccess.header(draws,n,f.renderedHeader);f.headerSerial=preparation;f.headerDraws=draws;
            }
        }
    }
    record Part(Frozen frame,int material) {}
    record HeldDraw(int index,Matrix4f current,Matrix4f previous,Matrix4f older) {
        Matrix4f sample(PmxPresentation.Timing timing,PmxDrawMesh.FrameData data,
                PmxDrawMesh.FrameData previousData,Matrix4f dest) {
            var a=timing.from()==data?current:timing.from()==previousData?previous:older;
            var b=timing.to()==data?current:timing.to()==previousData?previous:older;
            if(a==null||b==null)return dest.set(current);
            // Blend the grip endpoints with the same weights as the skinned hand vertices.
            dest.set(a).lerp(b,timing.weight());
            if(Math.abs(dest.determinant3x3())<1e-5f)
                dest.set(timing.weight()<.5f?a:b);
            return dest;
        }
    }
    static final class Frozen {
        final ModelSession session;final PmxDrawMesh mesh;final PmxDrawMesh.FrameData data;final ModelDraws draws;
        final PmxDrawMesh.FrameData previous,older;
        final Vector3f low,high;
        final ModelSession.Materials materials;
        zombie.core.textures.Texture[] boundTextures;
        ArrayList<HeldDraw> held;
        PmxPresentation.Timing timing;
        final int first,originalEnd;int customEnd;
        final int[] originalFlags,originalMotion;final float[] originalRecords;
        long preparedSerial=Long.MIN_VALUE,headerSerial=Long.MIN_VALUE;ModelDraws headerDraws;float[] renderedHeader;
        Frozen(ModelSession session,PmxDrawMesh mesh,PmxDrawMesh.FrameData data,ModelDraws draws,int first,int end) {
            this.session=session;this.mesh=mesh;this.data=data;this.draws=draws;this.first=first;originalEnd=end;customEnd=end;materials=session.materials;
            previous=session.previousFrozen;older=session.olderFrozen;
            if(previous==null){low=high=null;}
            else {
                low=new Vector3f(Float.POSITIVE_INFINITY);high=new Vector3f(Float.NEGATIVE_INFINITY);
                for(PmxDrawMesh.FrameData snapshot:new PmxDrawMesh.FrameData[]{data,previous,older})if(snapshot!=null){
                    snapshot.includeBounds(low,high);
                }
                float pad=session.shading==null?0:session.shading.renderProfile().outlineMaxNative;low.sub(pad,pad,pad);high.add(pad,pad,pad);
            }
            originalFlags=new int[end-first];originalMotion=new int[(end-first)*6];originalRecords=new float[(end-first)*68];
            System.arraycopy(DrawAccess.values(draws),first*68,originalRecords,0,originalRecords.length);
            for(int n=first;n<end;n++){originalFlags[n-first]=draws.flags(n);DrawAccess.saveMotion(draws,n,originalMotion,(n-first)*6);}
        }
        void addHeld(HeldDraw part) {
            if(held==null)held=new ArrayList<>();
            held.add(part);
        }
        void presentHeld(ModelDraws drawn) {
            Matrix4f root=DrawAccess.matrix(drawn,originalEnd),local=new Matrix4f(),world=new Matrix4f();
            for(HeldDraw part:held) {
                root.mul(part.sample(timing,data,previous,local),world);
                DrawAccess.matrix(drawn,part.index,world);
                var mesh=DrawAccess.mesh(drawn,part.index);
                drawn.bounds(part.index,world,mesh.minXyz,mesh.maxXyz,1);
            }
        }
        void upload(long serial,float phase,boolean cut) {
            if(preparedSerial==serial)return;preparedSerial=serial;timing=null;
            if(session.retired){restore();return;}
            try {
                if(boundTextures==null)boundTextures=session.viewpointModel.textures(materials);
                timing=PmxPresentation.timing(data,previous,older,phase,cut);
                PmxDrawMesh.FrameData sampled=session.viewpointModel.presentation.sample(data,timing,cut);
                mesh.uploadStreamed(sampled,materials.shadow());renderedHeader=sampled.shadingHead();
            }catch(Throwable e){restore();session.fail(e);}
        }
        void restore() {
            System.arraycopy(originalRecords,0,DrawAccess.values(draws),first*68,originalRecords.length);
            for(int n=first;n<originalEnd;n++){DrawAccess.flags(draws,n,originalFlags[n-first]);DrawAccess.restoreMotion(draws,n,originalMotion,(n-first)*6);}
            for(int n=originalEnd;n<customEnd;n++)DrawAccess.flags(draws,n,0);
        }
    }
}
