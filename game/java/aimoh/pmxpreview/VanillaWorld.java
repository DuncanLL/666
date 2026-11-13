package aimoh.pmxpreview;

import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel.AnimatedModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.iso.IsoCamera;

/** Adapts shared, CPU-skinned frames to the stock static character shader. */
public final class VanillaWorld {
    private static final ConcurrentHashMap<ModelSlotRenderData,Draw> QUEUED=new ConcurrentHashMap<>();
    private static final ThreadLocal<ModelSlotRenderData> WORLD=new ThreadLocal<>();

    private record Hand(ModelInstance root,Matrix4f correction) {}

    private static final class Draw {
        final ModelSession session;
        final ModelSession.Snapshot snapshot;
        final ModelInstance body;
        final Hand primary,secondary;
        final IdentityHashMap<ModelInstanceRenderData,Matrix4f> heldTransforms=new IdentityHashMap<>();
        boolean painted;

        Draw(ModelSession session,ModelSession.Snapshot snapshot,ModelInstance body,Hand primary,Hand secondary) {
            this.session=session;this.snapshot=snapshot;this.body=body;this.primary=primary;this.secondary=secondary;
        }

        Hand hand(ModelInstanceRenderData part) {
            if(primary==null&&secondary==null)return null;
            for(AnimatedModelInstanceRenderData node=part;node!=null;node=node.parent) {
                if(primary!=null&&node.modelInstance==primary.root())return primary;
                if(secondary!=null&&node.modelInstance==secondary.root())return secondary;
            }
            // The ready-model fallback can lack a render-data parent for a held child.
            for(ModelInstance node=part.modelInstance;node!=null;node=node.parent) {
                if(primary!=null&&node==primary.root())return primary;
                if(secondary!=null&&node==secondary.root())return secondary;
            }
            return null;
        }
    }

    public static void capture(ModelSlotRenderData slot) {
        QUEUED.remove(slot);
        IsoGameCharacter actor=slot.character;
        if(actor==null||!Main.hasAppearance(actor)||ViewpointMode.active()
                ||slot.animPlayer==null||slot.animPlayer.getSkinningData()==null)return;
        ModelSession session=Main.sessionForCapture(actor);
        if(session==null)return;
        var mainItem=actor.getPrimaryHandItem();
        var offItem=actor.getSecondaryHandItem();
        boolean holding=mainItem!=null||offItem!=null;
        ModelInstance body=slot.modelSlot.model;
        ModelInstanceRenderData bodyData=null,primaryData=null,secondaryData=null;
        if(actor.primaryHandModel!=null||actor.secondaryHandModel!=null)for(var data:slot.modelData) {
            if(data.modelInstance==body)bodyData=data;
            if(data.modelInstance==actor.primaryHandModel)primaryData=data;
            if(data.modelInstance==actor.secondaryHandModel)secondaryData=data;
        }
        Matrix4f inverseBody=bodyData==null?null:new Matrix4f(bodyData.xfrm).transpose().invert();
        int calibrationSide=GripCalibration.heldSide(actor);
        var heldData=calibrationSide==1?primaryData:calibrationSide==0?secondaryData:null;
        Matrix4f weapon=heldData==null||!heldData.model.isStatic||inverseBody==null?null:
            new Matrix4f(inverseBody).mul(new Matrix4f(heldData.xfrm).transpose());
        long frame=IsoCamera.frameState.frameCount,nanos=System.nanoTime();
        boolean idle=IdleHandPose.eligible(actor,holding);
        try {
            if(!session.failed)session.update(frame,nanos,slot.animPlayer,holding,actor.getX(),actor.getY(),actor.getZ(),false,idle,false,
                GripCalibration.request(actor,session.entry.id(),weapon),false);
        } catch(Throwable failure) { session.fail(failure); }
        ModelSession selected=Main.completeCapture(actor,session);
        if(selected==null||selected.failed)return;
        try {
            if(selected!=session)selected.update(frame,nanos,slot.animPlayer,holding,actor.getX(),actor.getY(),actor.getZ(),false,idle,false,
                GripCalibration.request(actor,selected.entry.id(),weapon),false);
            if(!selected.ready)return;
            ModelSession.Snapshot snapshot=selected.snapshot();
            if(snapshot==null)return;
            Matrix4f targetBody=bodyData==null?null:placement(bodyData,selected.scale);
            Hand primary=hand(selected,slot,actor.primaryHandModel,primaryData,1,mainItem==null?"":mainItem.getType(),inverseBody,targetBody);
            Hand secondary=hand(selected,slot,actor.secondaryHandModel,secondaryData,0,offItem==null?"":offItem.getType(),inverseBody,targetBody);
            QUEUED.put(slot,new Draw(selected,snapshot,body,primary,secondary));
        } catch(Throwable failure) { selected.fail(failure); }
    }

    private static Hand hand(ModelSession session,ModelSlotRenderData slot,ModelInstance root,
            ModelInstanceRenderData data,int side,String key,Matrix4f inverseBody,Matrix4f targetBody) {
        if(root==null)return null;
        Matrix4f correction=null;
        if(data!=null&&data.model.isStatic&&inverseBody!=null&&inverseBody.isFinite()&&session.profile.optBoolean("palm_ready")) {
            Matrix4f original=new Matrix4f(data.xfrm).transpose();
            if(original.isFinite()&&Math.abs(original.determinant())>1e-8f) {
                Matrix4f local=new Matrix4f(inverseBody).mul(original);
                Matrix4f mapped=session.attachment(root,side,key,local,slot.animPlayer);
                Matrix4f candidate=new Matrix4f(targetBody).mul(mapped).mul(original.invert());
                if(candidate.isFinite())correction=candidate;
            }
        }
        return new Hand(root,correction);
    }

    public static void begin(ModelSlotRenderData slot) {
        Draw draw=QUEUED.get(slot);
        if(draw==null)WORLD.remove();
        else {draw.painted=false;WORLD.set(slot);}
    }
    public static void end() { WORLD.remove(); }
    public static void release(ModelSlotRenderData slot) { QUEUED.remove(slot); }

    public static boolean replace(ModelSlotRenderData slot,ModelInstanceRenderData data) {
        Draw draw=QUEUED.get(slot);
        if(draw==null||draw.session.retired||draw.session.failed)return false;
        Hand hand=draw.hand(data);
        if(hand!=null) {
            if(WORLD.get()==slot&&draw.painted&&hand.correction()!=null&&data.model.isStatic) {
                // RenderList keeps the per-slot properties for queued/instanced equipment draws.
                Matrix4f transform=draw.heldTransforms.computeIfAbsent(data,part->
                    new Matrix4f(hand.correction()).mul(new Matrix4f(part.xfrm).transpose()).transpose());
                data.xfrm.set(transform);
                data.properties.SetMatrix4("transform",transform);
            }
            return false;
        }
        if(WORLD.get()!=slot)return true;
        if(draw.body!=data.modelInstance)return draw.painted;
        ReadyRoomPreview.State saved=new ReadyRoomPreview.State();
        Shader shader=null;
        try {
            var mesh=draw.session.plainMesh(draw.snapshot.frame());
            var textures=draw.session.preparePlainMaterials(draw.snapshot.materials());
            mesh.bindGeometry(draw.snapshot.frame());
            shader=ShaderManager.instance.getOrCreateShader("basicEffect",true,false);
            if(shader==null||shader.getID()==0)return false;
            GL20.glUseProgram(shader.getID());
            Core core=Core.getInstance();
            shader.getShaderProgram().setValue("ModelViewProjection",
                    new Matrix4f(core.projectionMatrixStack.peek()).mul(core.modelViewMatrixStack.peek()));
            shader.startCharacter(slot,data);
            shader.setTransformMatrix(placement(data,draw.session.scale),false);
            shader.setScale(slot.finalScale);
            shader.setHueShift(0);
            shader.setTint(1,1,1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);
            for(int part=0;part<mesh.materials.length;part++) {
                int material=mesh.materials[part];
                if(draw.snapshot.frame().materials()[material*12+3]<=0)continue;
                shader.setTexture(textures[material],"Texture",0);
                GL11.glDrawElements(GL11.GL_TRIANGLES,mesh.counts[part],GL11.GL_UNSIGNED_INT,(long)mesh.firsts[part]*4);
            }
            draw.painted=true;
            return true;
        } catch(Exception | LinkageError failure) {
            if(draw.snapshot.frame().gpuPose()!=null)draw.session.disableGpu(failure);
            else draw.session.fail(failure);
            return false;
        } finally {
            saved.restore();
            SpriteRenderer.ringBuffer.restoreVbos=true;SpriteRenderer.ringBuffer.restoreBoundTextures=true;
        }
    }

    private static Matrix4f placement(ModelInstanceRenderData data,float scale) {
        // Shader.startCharacter uploads xfrm with transpose=true.
        return new Matrix4f(data.xfrm).transpose().scale(1/scale,1/scale,-1/scale);
    }
}
