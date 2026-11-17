package aimoh.pmxpreview;

import aimoh.motion.PmxDrawMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import viewpoint.render.ModelDraws;
import zombie.asset.AssetPath;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;

public final class GripPauseCheck {
    public static void main(String[] args) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var session = (ModelSession)((sun.misc.Unsafe)field.get(null)).allocateInstance(ModelSession.class);
        var backend = new ViewpointModel(session);
        var mesh = new ModelMesh(new AssetPath("test-paused-grip"),null,new ModelMesh.MeshAssetParams());
        var gear = new DrawAccess.Gear(null,1,"held");
        var pose = new PmxDrawMesh.FrameData(new float[]{.35f,.12f,.26f},new float[]{0,1,0},new float[0]);
        var local = new Matrix4f().translation(.42f,.8f,.28f).rotateY(.2f);
        var grip = new Vector3f(.25f,.12f,.18f);
        float largest = 0;
        int count = 0;
        int[] resolutions={0};
        var sourceGear=new ModelInstanceRenderData();
        var sourceBody=new Matrix4f().translation(.4f,-.7f,.2f).rotateZ(.19f);
        sourceGear.xfrm.set(new Matrix4f(sourceBody).mul(local).transpose());
        var mount=Bridge.heldSource(sourceGear,new Matrix4f(sourceBody).invert());
        if(!mount.equals(local,1e-6f))throw new AssertionError("Game slot attachment was changed");
        for (float yaw : new float[]{0,.32f,-.65f,1.2f,-1.3f,2.4f}) {
            var world = new Matrix4f().translation(yaw,0,.31f).rotateY(yaw);
            var presented = new PmxDrawMesh.FrameData(pose.positions(),pose.normals(),pose.materials());
            var capturedLocal = new Matrix4f(local).rotateY(yaw).translate(yaw*.18f,0,0);
            var drawn=new ModelDraws();
            int item=drawn.add(mesh,null,null,new Matrix4f(world).mul(capturedLocal),-1,-1,0,3);
            int body=drawn.add(null,null,null,world,-1,-1,0,3);
            var frozen=new Bridge.Frozen(session,null,presented,drawn,0,body);
            backend.retainHeldFrames(presented,null,null);
            frozen.addHeld(backend.captureHeld(frozen,item,null,gear,mesh,() -> {
                if(++resolutions[0]==1)return mount;
                return capturedLocal;
            }));
            frozen.timing=PmxPresentation.timing(presented,null,null,.25f,false);
            frozen.presentHeld(drawn);
            var actual=DrawAccess.matrix(drawn,item).transformPosition(new Vector3f(grip));
            var expected=new Matrix4f(world).mul(local).transformPosition(new Vector3f(grip));
            largest=Math.max(largest,actual.distance(expected));
            count++;
        }
        if(resolutions[0]!=1)throw new AssertionError("Resolved the same hand attachment "+resolutions[0]+" times");
        if (largest>0.00002f) throw new AssertionError("Paused weapon changed its grip transform: "+largest);
        var advanced=new PmxDrawMesh.FrameData(pose.positions().clone(),pose.normals().clone(),pose.materials());
        var freshDraws=new ModelDraws();
        var fresh=new Bridge.Frozen(session,null,advanced,freshDraws,0,0);
        var next=new Matrix4f(local).translate(.4f,0,0);
        backend.retainHeldFrames(advanced,null,null);
        if (!backend.captureHeld(fresh,0,null,gear,mesh,() -> next).current().equals(next,1e-6f))
            throw new AssertionError("A new simulation pose did not accept the new held transform");
        var swap=new DrawAccess.Gear(null,1,"replacement");
        var replacement=new Matrix4f(local).rotateX(.4f);
        if (!backend.captureHeld(fresh,0,null,swap,mesh,() -> replacement).current().equals(replacement,1e-6f))
            throw new AssertionError("A new item reused a previous item's mount");
        System.out.printf(java.util.Locale.ROOT,"PAUSE_GRIP_PASS cases=%d max_local_grip_error=%.8f fresh_pose_and_item=true one_mount_per_pose=true%n",count,largest);
    }
}
