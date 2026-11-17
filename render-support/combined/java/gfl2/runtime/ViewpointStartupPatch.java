package gfl2.runtime;

import gfl2.render.UnifiedStartup;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "viewpoint.Main", methodName = "main")
public final class ViewpointStartupPatch {
    @Patch.OnEnter
    public static void enter() {
        UnifiedStartup.start();
    }
}
