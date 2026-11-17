package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import viewpoint.render.SceneData;
import viewpoint.render.ModelDraws;

@Patch(className="viewpoint.render.ModelPass",methodName="prepare")
public final class RenderPatch {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) SceneData scene,@Patch.AllArguments Object[] arguments) {
        if(arguments.length>2&&arguments[2] instanceof Float phase)Bridge.prepare(scene,phase);
        else Bridge.prepare(scene);
    }

    @Patch(className="viewpoint.render.ModelBlend",methodName="drawn")
    public static final class HeldItems {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) ModelDraws captured,@Patch.Return ModelDraws drawn) {
            Bridge.presentHeld(captured,drawn);
        }
    }
}
