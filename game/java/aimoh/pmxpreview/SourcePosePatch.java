package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.util.vector.Matrix4f;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.SkinningData;

public final class SourcePosePatch {
    @Patch(className="zombie.core.skinnedmodel.animation.AnimationPlayer",methodName="getModelTransformAt")
    public static final class Bone {
        @Patch.OnExit
        public static void exit(@Patch.This AnimationPlayer player,@Patch.Argument(0) int bone,@Patch.Return Matrix4f value) {
            SourcePoseGuard.read(player,bone,value);
        }
    }
    @Patch(className="zombie.core.skinnedmodel.animation.AnimationPlayer",methodName="getSkinTransforms")
    public static final class Palette {
        @Patch.OnEnter
        public static void enter(@Patch.This AnimationPlayer player,@Patch.Argument(0) SkinningData skin) {
            if(skin!=null)SourcePoseGuard.beforeSkinning(player);
        }
    }
    @Patch(className="zombie.core.skinnedmodel.animation.AnimationPlayer",methodName="updateModelTransformsInternal")
    public static final class Animation {
        @Patch.OnExit
        public static void exit(@Patch.This AnimationPlayer player) { SourcePoseGuard.animationReady(player); }
    }
}
