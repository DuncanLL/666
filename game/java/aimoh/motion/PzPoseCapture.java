package aimoh.motion;

import zombie.core.skinnedmodel.animation.AnimationPlayer;
import org.lwjgl.util.vector.Matrix4f;

/** Freeze final game model matrices in the existing retargeter's column order. */
public final class PzPoseCapture {
    private PzPoseCapture() {}

    public static float[] capture(AnimationPlayer player, int bones) {
        if (player == null || bones <= 0) throw new IllegalArgumentException("Missing source skeleton");
        float[] result = new float[Math.multiplyExact(bones, 16)];
        for (int i = 0; i < bones; i++) {
            Matrix4f m = player.getModelTransformAt(i);
            int at = i * 16;
            result[at] = m.m00; result[at + 1] = m.m10;
            result[at + 2] = m.m20; result[at + 3] = m.m30;
            result[at + 4] = m.m01; result[at + 5] = m.m11;
            result[at + 6] = m.m21; result[at + 7] = m.m31;
            result[at + 8] = m.m02; result[at + 9] = m.m12;
            result[at + 10] = m.m22; result[at + 11] = m.m32;
            result[at + 12] = m.m03; result[at + 13] = m.m13;
            result[at + 14] = m.m23; result[at + 15] = m.m33;
        }
        for (float value : result) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite source pose");
        return result;
    }
}
