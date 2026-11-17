package aimoh.pmxpreview;

import aimoh.motion.PmxDrawMesh;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import org.joml.Matrix4f;
import viewpoint.render.ModelDraws;
import viewpoint.render.Retirement;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.textures.Texture;

/** Render resources and motion history used exclusively by the Viewpoint adapter. */
final class ViewpointModel implements AutoCloseable {
    private final ModelSession session;
    private final TextureFactory textures = new TextureFactory(true);
    private PmxDrawMesh mesh;
    volatile PmxDrawMesh prepared;
    final PmxPresentation presentation = new PmxPresentation();
    private final IdentityHashMap<ModelMesh, DrawHistory> history = new IdentityHashMap<>();
    private long historyFrame = Long.MIN_VALUE;
    private final IdentityHashMap<float[],IdentityHashMap<ModelInstance,HeldPose>> held = new IdentityHashMap<>();

    private record HeldPose(DrawAccess.Gear gear,ModelMesh mesh,Matrix4f local) {
        boolean matches(DrawAccess.Gear next,ModelMesh nextMesh) {
            return mesh==nextMesh&&gear.root==next.root&&gear.side==next.side&&gear.key.equals(next.key);
        }
    }

    private static final class DrawHistory {
        ModelDraws draws;
        int index;
        long frame;
    }

    ViewpointModel(ModelSession session) { this.session = session; }

    boolean ready() {
        prepared = mesh == null ? null : mesh.readyMesh();
        return prepared != null;
    }

    void upload(PmxDrawMesh.FrameData frame, ModelSession.Materials state) throws Exception {
        Texture[] material = textures(state);
        if (mesh == null) mesh = new PmxDrawMesh(session.api, session.model.handle(), material, session.model.appearance(), session.shading);
        mesh.upload(frame);
        ready();
    }

    Texture[] textures(ModelSession.Materials state) throws Exception {
        Texture[] result = textures.prepare(state.paths(), state.factors(), state.version(), state.epoch(), session.shading);
        if (mesh != null) mesh.updateOutlineTextures(result);
        return result;
    }

    int textureCount() { return textures.liveTextures; }
    long textureUploads() { return textures.uploads; }

    synchronized boolean followDraw(ModelDraws draws, int index, long frame) {
        if (historyFrame != frame) {
            history.values().removeIf(old -> old.frame < frame - 1);
            historyFrame = frame;
        }
        ModelMesh mesh = DrawAccess.mesh(draws,index);
        DrawHistory old = history.computeIfAbsent(mesh, ignored -> new DrawHistory());
        if (old.frame == frame && old.draws == draws && old.index == index) return false;
        boolean continuous = old.draws != null && old.frame == frame-1 && old.draws != draws
                && old.index < old.draws.count() && DrawAccess.mesh(old.draws,old.index) == mesh;
        if (continuous) draws.motion(index,old.draws,old.index,0,false);
        old.draws=draws;old.index=index;old.frame=frame;
        return continuous;
    }

    synchronized void retainHeldFrames(PmxDrawMesh.FrameData current,PmxDrawMesh.FrameData previous,PmxDrawMesh.FrameData older) {
        if(held.isEmpty())return;
        float[] now=current.positions(),before=previous==null?null:previous.positions(),earlier=older==null?null:older.positions();
        held.keySet().removeIf(pose -> pose!=now&&pose!=before&&pose!=earlier);
    }

    synchronized Bridge.HeldDraw captureHeld(Bridge.Frozen draw,int index,ModelInstance instance,
            DrawAccess.Gear gear,ModelMesh mesh,Supplier<Matrix4f> resolve) {
        // Appearance-only frame copies share the same skinned vertices and attachment pose.
        var poses=held.computeIfAbsent(draw.data.positions(),ignored -> new IdentityHashMap<>());
        var pose=poses.get(instance);
        if(pose==null||!pose.matches(gear,mesh)){
            pose=new HeldPose(gear,mesh,resolve.get());
            poses.put(instance,pose);
        }
        return new Bridge.HeldDraw(index,pose.local,heldAt(draw.previous,instance,gear,mesh),heldAt(draw.older,instance,gear,mesh));
    }

    private Matrix4f heldAt(PmxDrawMesh.FrameData frame,ModelInstance instance,DrawAccess.Gear gear,ModelMesh mesh) {
        var poses=frame==null?null:held.get(frame.positions());
        var pose=poses==null?null:poses.get(instance);
        return pose!=null&&pose.matches(gear,mesh)?pose.local:null;
    }

    static void retire(Runnable close) { Retirement.retire(close::run); }
    static void retireDrawing(Runnable close) { Retirement.retireDrawing(close::run); }

    @Override public void close() {
        try { aimoh.motion.ResourceCleanup.close(mesh, textures); }
        finally { prepared=null; mesh=null; history.clear(); held.clear(); presentation.clear(); }
    }
}
