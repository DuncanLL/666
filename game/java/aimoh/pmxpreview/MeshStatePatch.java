package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.opengl.GL20;

/** Disabled weight arrays use context state, which other draws can invalidate. */
@Patch(className="viewpoint.render.ModelPass",methodName="cull")
public final class MeshStatePatch {
    @Patch.OnEnter
    public static void enter() {
        // Each batch supplies arrays for skinned meshes; static meshes need zero weights.
        GL20.glVertexAttrib4f(2,0,0,0,0);
    }
}
