package aimoh.pmxpreview;

import aimoh.gf2.bridge.UnifiedStartup;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "viewpoint.Main", methodName = "main")
public final class ViewpointStartupPatch {
    @Patch.OnEnter
    public static void enter() {
        UnifiedStartup.start();
    }
}
