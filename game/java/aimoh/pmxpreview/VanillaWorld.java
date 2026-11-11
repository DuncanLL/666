package aimoh.pmxpreview;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
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

    private static final class Draw {
        final ModelSession session;
        final ModelSession.Snapshot snapshot;
        final ModelInstance body;
        final Set<ModelInstance> held;
        boolean painted;

        Draw(ModelSession session,ModelSession.Snapshot snapshot,ModelInstance body,Set<ModelInstance> held) {
            this.session=session;this.snapshot=snapshot;this.body=body;this.held=held;
        }
    }

    public static void capture(ModelSlotRenderData slot) {
        QUEUED.remove(slot);
        IsoGameCharacter actor=slot.character;
        if(actor==null||!Main.hasAppearance(actor)||ViewpointMode.active()
                ||slot.animPlayer==null||slot.animPlayer.getSkinningData()==null)return;
        ModelSession session=Main.sessionForCapture(actor);
        if(session==null)return;
        boolean holding=actor.getPrimaryHandItem()!=null||actor.getSecondaryHandItem()!=null;
        long frame=IsoCamera.frameState.frameCount,nanos=System.nanoTime();
        boolean idle=IdleHandPose.eligible(actor,holding);
        try {
            if(!session.failed)session.update(frame,nanos,slot.animPlayer,holding,actor.getX(),actor.getY(),actor.getZ(),false,idle,false,null,false);
        } catch(Throwable failure) { session.fail(failure); }
        ModelSession selected=Main.completeCapture(actor,session);
        if(selected==null||selected.failed)return;
        try {
            if(selected!=session)selected.update(frame,nanos,slot.animPlayer,holding,actor.getX(),actor.getY(),actor.getZ(),false,idle,false,null,false);
            if(!selected.ready)return;
            ModelSession.Snapshot snapshot=selected.snapshot();
            if(snapshot==null)return;
            Set<ModelInstance> held=Collections.newSetFromMap(new IdentityHashMap<>());
            collect(actor.primaryHandModel,held);collect(actor.secondaryHandModel,held);
            QUEUED.put(slot,new Draw(selected,snapshot,slot.modelSlot.model,held));
        } catch(Throwable failure) { selected.fail(failure); }
    }

    private static void collect(ModelInstance instance,Set<ModelInstance> held) {
        if(instance==null||!held.add(instance))return;
        for(ModelInstance child:instance.sub)collect(child,held);
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
        if(draw==null||draw.session.retired||draw.session.failed||draw.held.contains(data.modelInstance))return false;
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
