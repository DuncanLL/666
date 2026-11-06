package com.shiroha.mmdskin;
import java.nio.ByteBuffer;
// Declarations of upstream JNI and the independently implemented Aimoh input ABI.
public final class NativeFunc {
    public static int aimohFacadeVersion() { return 1; }
    public native String GetVersion();
    public native long LoadModelPMX(String file, String directory, long layers);
    public native void DeleteModel(long model);
    public native void UpdateModel(long model, float seconds);
    public native void UpdateAnimationOnly(long model, float seconds);
    public native void EvaluateSkeleton(long model);
    public native long GetVertexCount(long model);
    public native int GetBoneCount(long model);
    public native String GetBoneMetadata(long model);
    public native long GetPoss(long model);
    public native long GetNormals(long model);
    public native long GetUVs(long model);
    public native long GetIndexElementSize(long model);
    public native long GetIndexCount(long model);
    public native long GetIndices(long model);
    public native long GetMaterialCount(long model);
    public native String GetMaterialTex(long model, long index);
    public native long GetMaterialDiffuse(long model, long index);
    public native float GetMaterialAlpha(long model, long index);
    public native boolean IsMaterialVisible(long model, int index);
    public native void SetMaterialVisible(long model, int index, boolean visible);
    public native int CopyMaterialMorphResultsToBuffer(long model, ByteBuffer buffer);
    public native boolean GetMaterialBothFace(long model, long index);
    public native long GetSubMeshCount(long model);
    public native int GetSubMeshMaterialID(long model, long index);
    public native int GetSubMeshBeginIndex(long model, long index);
    public native int GetSubMeshVertexCount(long model, long index);
    public native void CopyDataToByteBuffer(ByteBuffer buffer, long pointer, long bytes);
    public native void InitGpuSkinningData(long model);
    public native int CopySkinningMatricesToBuffer(long model, ByteBuffer buffer);
    public native int SetBoneAdditiveTransformBatch(long model, int[] indices, float[] qps);
    public native void ClearBoneAdditiveTransforms(long model);
    public native void SetPhysicsEnabled(long model, boolean enabled);
    public native boolean IsPhysicsEnabled(long model);
    public native boolean InitPhysics(long model);
    public native boolean HasPhysics(long model);
    public native void ResetPhysics(long model);
    public native String GetPhysicsDebugInfo(long model);
    public native void SetModelPositionAndYaw(long model, float x, float y, float z, float yaw);
    public native void SetAutoBlinkEnabled(long model, boolean enabled);
    public native void SetFirstPersonMode(long model, boolean enabled);
    public native void SetPZLegIKSuppressed(long model, boolean suppressed);
    public native long GetMorphCount(long model);
    public native String GetMorphName(long model, int index);
    public native float GetMorphWeight(long model, int index);
    public native void SetPZAnimationState(long model, long[] animations, float[] frames,
                                          boolean[] additive, int[] morphs, float[] weights);
    public native String GetRuntimeStats();
}
