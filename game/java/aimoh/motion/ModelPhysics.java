package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;

/** One native physics policy per model. Intermediate pose evaluations stay at dt=0. */
final class ModelPhysics {
    private final NativeFunc api;
    private final long handle;
    private final boolean available;
    private boolean requested, active, resetPending;
    private long advancingFrames;

    ModelPhysics(NativeFunc api, long handle) {
        this.api=api;this.handle=handle;available=api.HasPhysics(handle);
        active=api.IsPhysicsEnabled(handle);
    }
    void request(boolean value) {
        requested=value && available;
        if (!requested && active) { api.SetPhysicsEnabled(handle,false);active=false; }
    }
    boolean enabled() { return requested; }
    void reset() { resetPending=true; }
    void motion(float x,float y,float z,float yaw) {
        if (!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(yaw))
            throw new IllegalArgumentException("Invalid model motion");
        api.SetModelPositionAndYaw(handle,x,y,z,yaw);
    }
    void finish(float seconds) {
        if (!Float.isFinite(seconds)||seconds<0) throw new IllegalArgumentException("Invalid frame time");
        // Enabling/resetting here initializes dynamic bodies from the completed pose, not a calibration pass.
        if (requested && !active) { api.SetPhysicsEnabled(handle,true);active=true;resetPending=false; }
        else if (active && resetPending) api.ResetPhysics(handle);
        resetPending=false;
        api.UpdateModel(handle,seconds);
        if (active && seconds>0) advancingFrames++;
    }
    long advancingFrames() { return advancingFrames; }
}
