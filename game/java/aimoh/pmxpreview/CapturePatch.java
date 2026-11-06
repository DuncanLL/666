package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import viewpoint.core.Frame;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;

@Patch(className="viewpoint.models.ModelCapture",methodName="capture")
public final class CapturePatch {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) Frame frame) { Bridge.begin(frame); }

    @Patch.OnExit
    public static void exit(@Patch.Argument(0) Frame frame,@Patch.Argument(1) ModelSlotRenderData slot,
            @Patch.Argument(2) IsoGameCharacter actor,@Patch.Argument(4) boolean hideHead,@Patch.Return boolean captured) {
        Bridge.end(frame,slot,actor,hideHead,captured);
    }
}
