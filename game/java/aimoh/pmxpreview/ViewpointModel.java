package aimoh.pmxpreview;

import aimoh.motion.PmxDrawMesh;
import java.util.IdentityHashMap;
import viewpoint.render.ModelDraws;
import viewpoint.render.Retirement;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.textures.Texture;

/** Render resources and motion history used exclusively by the Viewpoint adapter. */
final class ViewpointModel implements AutoCloseable {
    private final ModelSession session;
    private final TextureFactory textures = new TextureFactory(true);
    private PmxDrawMesh mesh;
    volatile PmxDrawMesh prepared;
    final PmxPresentation presentation = new PmxPresentation();
    private final IdentityHashMap<ModelMesh, DrawHistory> history = new IdentityHashMap<>();

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
        history.values().removeIf(old -> old.frame < frame - 1);
        ModelMesh mesh = DrawAccess.mesh(draws,index);
        DrawHistory old = history.computeIfAbsent(mesh, ignored -> new DrawHistory());
        if (old.frame == frame && old.draws == draws && old.index == index) return false;
        boolean continuous = old.draws != null && old.frame == frame-1 && old.draws != draws
                && old.index < old.draws.count() && DrawAccess.mesh(old.draws,old.index) == mesh;
        if (continuous) draws.motion(index,old.draws,old.index,0,false);
        old.draws=draws;old.index=index;old.frame=frame;
        return continuous;
    }

    static void retire(Runnable close) { Retirement.retire(close::run); }
    static void retireDrawing(Runnable close) { Retirement.retireDrawing(close::run); }

    @Override public void close() {
        try { aimoh.motion.ResourceCleanup.close(mesh, textures); }
        finally { prepared=null; mesh=null; history.clear(); presentation.clear(); }
    }
}
