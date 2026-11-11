package aimoh.pmxpreview;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.iso.IsoCell;

/** Actor identity substitute only; actual AnimationPlayer, tracks and matrices are used. */
public final class SourcePoseFixture extends IsoGameCharacter {
    private AnimationPlayer source;
    private static volatile float sink;
    private SourcePoseFixture(){super((IsoCell)null,0,0,0);}
    @Override public AnimationPlayer getAnimationPlayer(){return source;}
    public static SourcePoseFixture owner(AnimationPlayer player)throws Exception{
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        SourcePoseFixture actor=(SourcePoseFixture)((sun.misc.Unsafe)f.get(null)).allocateInstance(SourcePoseFixture.class);
        actor.source=player;player.setIsoGameCharacter(actor);return actor;
    }
    public static void watched(SourcePoseFixture actor,boolean value)throws Exception{
        Field f=Main.class.getDeclaredField("poseOwners");f.setAccessible(true);IdentityHashMap<IsoGameCharacter,SourcePoseGuard.AnimationPose> owners=new IdentityHashMap<>();if(value)owners.put(actor,new SourcePoseGuard.AnimationPose());f.set(null,owners);
    }
    public static Matrix4f raw(AnimationPlayer player,int bone)throws Exception{
        Field f=AnimationPlayer.class.getDeclaredField("modelTransforms");f.setAccessible(true);return ((Matrix4f[])f.get(player))[bone];
    }
    public static void evaluateAnimation(AnimationPlayer player)throws Exception{
        var m=AnimationPlayer.class.getDeclaredMethod("updateModelTransformsInternal");m.setAccessible(true);m.invoke(player);
    }
    public static long bench(AnimationPlayer player,int count){
        int bones=player.getModelTransformsCount();float total=0;long start=System.nanoTime();
        for(int i=0;i<count;i++)total+=player.getModelTransformAt(i%bones).m00;
        sink=total;return System.nanoTime()-start;
    }
    public static long benchAnimation(AnimationPlayer player,int count)throws Exception{
        var method=AnimationPlayer.class.getDeclaredMethod("updateModelTransformsInternal");method.setAccessible(true);long start=System.nanoTime();
        for(int i=0;i<count;i++)method.invoke(player);
        return System.nanoTime()-start;
    }
}
