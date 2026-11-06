package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;

/** Bind the existing PMX material inputs around the Native model pass. */
@Patch(className="viewpoint.render.ModelPass",methodName="gbuffer")
public final class NativeMaterialPatch {
    @Patch.OnEnter
    public static void enter(@Patch.This Object pass) { NativePmxMaterials.bind(pass); }
    @Patch.OnExit
    public static void exit() { NativePmxMaterials.unbind(); }
}
