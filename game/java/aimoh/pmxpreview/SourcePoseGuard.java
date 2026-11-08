package aimoh.pmxpreview;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.util.vector.Matrix4f;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/** Render modifiers borrow model matrices; animation locals remain the source of truth. */
public final class SourcePoseGuard {
    private static final Method INVALIDATE=cacheInvalidator();
    private static final AtomicBoolean announced=new AtomicBoolean();
    static final class AnimationPose {
        AnimationPlayer player;
        Matrix4f[] model,local;
        boolean current;
    }
    private SourcePoseGuard() {}
    private static Method cacheInvalidator() {
        try{Method m=AnimationPlayer.class.getDeclaredMethod("resetSkinTransforms");m.setAccessible(true);return m;}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static boolean finite(Matrix4f m) {
        return m!=null&&Float.isFinite(m.m00)&&Float.isFinite(m.m01)&&Float.isFinite(m.m02)&&Float.isFinite(m.m03)
            &&Float.isFinite(m.m10)&&Float.isFinite(m.m11)&&Float.isFinite(m.m12)&&Float.isFinite(m.m13)
            &&Float.isFinite(m.m20)&&Float.isFinite(m.m21)&&Float.isFinite(m.m22)&&Float.isFinite(m.m23)
            &&Float.isFinite(m.m30)&&Float.isFinite(m.m31)&&Float.isFinite(m.m32)&&Float.isFinite(m.m33);
    }
    private static boolean usable(Matrix4f m) {
        if(!finite(m))return false;
        float determinant=m.determinant();return Float.isFinite(determinant)&&determinant!=0;
    }
    public static void animationReady(AnimationPlayer player) {
        AnimationPose pose=Main.sourceAnimationPose(player);if(pose==null)return;
        synchronized(player){
            Matrix4f[] raw=player.getSkinTransforms(null);int count=raw.length;
            if(pose.player!=player||pose.model==null||pose.model.length!=count){
                pose.player=player;pose.model=new Matrix4f[count];pose.local=new Matrix4f[count];
                for(int i=0;i<count;i++){pose.model[i]=new Matrix4f();pose.local[i]=new Matrix4f();}
            }
            pose.current=false;
            for(int i=0;i<count;i++){
                if(!finite(raw[i]))return;
                pose.model[i].load(raw[i]);player.getBoneTransform(i,pose.local[i]);
                if(!finite(pose.local[i]))return;
            }
            pose.current=true;
        }
    }
    public static void read(AnimationPlayer player,int bone,Matrix4f borrowed) {
        AnimationPose pose=Main.sourceAnimationPose(player);
        if(pose==null||borrowed==null||usable(borrowed))return;
        synchronized(player){
            if(usable(borrowed))return;
            Matrix4f current=player.getBoneModelTransform(bone,new Matrix4f());
            if(!usable(current)){
                // Only a finite base from this animation evaluation may repair derived local damage.
                if(!pose.current||pose.player!=player||bone>=pose.model.length||!usable(pose.model[bone]))return;
                current=pose.model[bone];player.boneTransforms[bone].set(pose.local[bone]);
            }
            borrowed.load(current);
            try{INVALIDATE.invoke(player);}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot invalidate repaired pose palette",e);}
            if(announced.compareAndSet(false,true))System.err.println("[AimohPMXPreview] Recovered presentation bone "+bone+" from current animation locals; no pose/physics reset");
        }
    }
    public static void beforeSkinning(AnimationPlayer player) {
        if(Main.sourceAnimationPose(player)==null)return;
        // The host palette reads the backing array directly, bypassing its bone getter.
        for(int bone=0;bone<player.getModelTransformsCount();bone++)player.getModelTransformAt(bone);
    }
}
