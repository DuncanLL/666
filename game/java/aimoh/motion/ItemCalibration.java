package aimoh.motion;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One item-local rigid adjustment shared by the desktop tool and game reader. */
public final class ItemCalibration {
    private ItemCalibration() {}
    public static float[] apply(float[] original, float[] adjustment) {
        if (original.length != 16 || adjustment.length != 16)
            throw new IllegalArgumentException("Expected two item matrices");
        for (float x : original) if (!Float.isFinite(x)) throw new IllegalArgumentException("Invalid item matrix");
        for (float x : adjustment) if (!Float.isFinite(x)) throw new IllegalArgumentException("Invalid item adjustment");
        Matrix4f local = new Matrix4f().set(adjustment);
        Vector3f scale = local.getScale(new Vector3f());
        if (Math.abs(scale.x-1)>.001f || Math.abs(scale.y-1)>.001f || Math.abs(scale.z-1)>.001f ||
            Math.abs(local.determinant()-1)>.001f || Math.abs(local.m03())>.0001f ||
            Math.abs(local.m13())>.0001f || Math.abs(local.m23())>.0001f || Math.abs(local.m33()-1)>.0001f)
            throw new IllegalArgumentException("Item calibration permits translation and rotation only");
        return new Matrix4f().set(original).mul(local).get(new float[16]);
    }
}
