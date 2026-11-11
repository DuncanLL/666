package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;

public final class VanillaWorldPatch {
    @Patch(className="zombie.core.skinnedmodel.model.ModelSlotRenderData",methodName="init")
    public static final class Capture {
        @Patch.OnExit public static void exit(@Patch.This ModelSlotRenderData slot) { VanillaWorld.capture(slot); }
    }

    @Patch(className="zombie.core.skinnedmodel.model.ModelSlotRenderData",methodName="performRenderCharacter")
    public static final class Pass {
        @Patch.OnEnter public static void enter(@Patch.This ModelSlotRenderData slot) { VanillaWorld.begin(slot); }
        @Patch.OnExit(onThrowable=Throwable.class) public static void exit() { VanillaWorld.end(); }
    }

    @Patch(className="zombie.core.skinnedmodel.model.Model",methodName="DrawChar")
    public static final class Paint {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.Argument(0) ModelSlotRenderData slot,
                @Patch.Argument(1) ModelInstanceRenderData data) { return VanillaWorld.replace(slot,data); }
    }

    @Patch(className="zombie.core.skinnedmodel.model.ModelSlotRenderData",methodName="postRender")
    public static final class Release {
        @Patch.OnExit public static void exit(@Patch.This ModelSlotRenderData slot) { VanillaWorld.release(slot); }
    }
}
