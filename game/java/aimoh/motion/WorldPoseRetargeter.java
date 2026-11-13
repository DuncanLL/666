package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Comparator;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rest-relative model-space transport, then parent inverse, following the
 * Godot realtime_retarget utility and Three SkeletonUtils hierarchy method.
 * Native MMD owns helper/append bones. There are no character-specific offsets.
 */
public final class WorldPoseRetargeter {
    private final NativeFunc api;
    private final long handle;
    private final int[] targetBones, sourceBones, parents, order, levels;
    private final float[] positions, neutralQps, qps;
    private final Matrix4f[] sourceRestInverse, targetReference;
    private final Matrix4f[] sourceReference;
    private final Matrix4f basis = new Matrix4f().scaling(1, 1, -1);
    private final ByteBuffer palette;
    private final float unitScale;
    private final int sourceMotion, targetRoot;
    private final Vector3f sourceMotionReference;
    private final Vector3f[] gripAxes = new Vector3f[2];
    private final Vector3f[] sourcePalmLocal = new Vector3f[2], targetPalmLocal = new Vector3f[2];
    private final int[] palmSourceHands = new int[2], palmTargetHands = new int[2];
    private int evaluations;
    private final ModelPhysics physics;
    private ForwardFingerPose fingerPreview;
    private float fingerPreviewWeight;

    public WorldPoseRetargeter(NativeFunc api, long handle, int[] sourceBones,
            int[] targetBones, int[] parents, float[] initialPositions,
            float[] sourceReferenceWorld, float[] neutralQps, float unitScale,
            int sourceRoot, int targetRoot) {
        this(api,handle,sourceBones,targetBones,parents,initialPositions,sourceReferenceWorld,neutralQps,unitScale,sourceRoot,targetRoot,sourceRoot);
    }

    public WorldPoseRetargeter(NativeFunc api, long handle, int[] sourceBones,
            int[] targetBones, int[] parents, float[] initialPositions,
            float[] sourceReferenceWorld, float[] neutralQps, float unitScale,
            int sourceRoot, int targetRoot, int sourceMotion) {
        this.api = api;
        this.handle = handle;
        physics = new ModelPhysics(api,handle);
        this.sourceBones = sourceBones.clone();
        this.targetBones = targetBones.clone();
        this.parents = parents.clone();
        positions = initialPositions.clone();
        this.neutralQps = neutralQps.clone();
        qps = neutralQps.clone();
        this.unitScale = unitScale;
        this.sourceMotion = sourceMotion;
        this.targetRoot = targetRoot;
        if (sourceBones.length != targetBones.length || neutralQps.length != 10 * targetBones.length ||
            initialPositions.length != 3 * parents.length || sourceReferenceWorld.length % 16 != 0)
            throw new IllegalArgumentException("Reference pose shape mismatch");
        palette = ByteBuffer.allocateDirect(parents.length * 64).order(ByteOrder.nativeOrder());
        sourceRestInverse = new Matrix4f[sourceReferenceWorld.length / 16];
        sourceReference = new Matrix4f[sourceRestInverse.length];
        for (int i = 0; i < sourceRestInverse.length; i++) {
            sourceReference[i] = sourceMatrix(sourceReferenceWorld, i);
            sourceRestInverse[i] = new Matrix4f(sourceReference[i]).setTranslation(0, 0, 0).invert();
        }
        if(sourceMotion<0 || sourceMotion>=sourceRestInverse.length)throw new IllegalArgumentException("Invalid source motion bone");
        sourceMotionReference = sourceMatrix(sourceReferenceWorld, sourceMotion).getTranslation(new Vector3f());
        api.SetBoneAdditiveTransformBatch(handle, this.targetBones, this.neutralQps);
        api.UpdateAnimationOnly(handle, 0);
        readPalette();
        targetReference = new Matrix4f[targetBones.length];
        for (int i = 0; i < targetBones.length; i++) targetReference[i] = joint(targetBones[i]);
        levels = new int[targetBones.length];
        for (int i = 0; i < targetBones.length; i++) {
            int parent = parents[targetBones[i]], steps = 0;
            while (parent >= 0) {
                for (int value : targetBones) if (value == parent) levels[i]++;
                parent = parents[parent];
                if (++steps > parents.length) throw new IllegalArgumentException("Target parent cycle");
            }
        }
        Integer[] sorted = new Integer[targetBones.length];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i;
        Arrays.sort(sorted, Comparator.comparingInt(i -> levels[i]));
        order = Arrays.stream(sorted).mapToInt(Integer::intValue).toArray();
    }

    private static Matrix4f sourceMatrix(float[] values, int index) {
        return new Matrix4f().set(Arrays.copyOfRange(values, index * 16, index * 16 + 16));
    }

    private void readPalette() {
        palette.clear();
        if (api.CopySkinningMatricesToBuffer(handle, palette) != parents.length)
            throw new IllegalStateException("Incomplete native skeleton palette");
    }

    private Matrix4f joint(int bone) {
        float[] values = new float[16];
        for (int i = 0; i < 16; i++) values[i] = palette.getFloat(bone * 64 + i * 4);
        return new Matrix4f().set(values).translate(positions[bone * 3], positions[bone * 3 + 1], positions[bone * 3 + 2]);
    }

    private void applyBody(float[] sourceWorld) {
        if (sourceWorld.length != sourceRestInverse.length * 16)
            throw new IllegalArgumentException("Source skeleton changed");
        System.arraycopy(neutralQps, 0, qps, 0, qps.length);
        api.SetBoneAdditiveTransformBatch(handle, targetBones, qps);
        api.EvaluateSkeleton(handle);
        evaluations = 1;
        int cursor = 0;
        while (cursor < order.length) {
            readPalette();
            int level = levels[order[cursor]];
            while (cursor < order.length && levels[order[cursor]] == level) {
                int index = order[cursor++], target = targetBones[index], source = sourceBones[index];
                Matrix4f delta = new Matrix4f(basis).mul(sourceMatrix(sourceWorld, source).setTranslation(0, 0, 0))
                    .mul(sourceRestInverse[source]).mul(basis);
                Quaternionf rotation = delta.getUnnormalizedRotation(new Quaternionf()).normalize();
                Matrix4f desired = new Matrix4f().rotation(rotation).mul(targetReference[index]);
                Quaternionf parent = parents[target] < 0 ? new Quaternionf() :
                    joint(parents[target]).getUnnormalizedRotation(new Quaternionf()).normalize();
                Quaternionf local = parent.invert().mul(desired.getUnnormalizedRotation(new Quaternionf()).normalize()).normalize();
                int offset = index * 10;
                qps[offset + 3] = local.x;
                qps[offset + 4] = local.y;
                qps[offset + 5] = -local.z;
                qps[offset + 6] = -local.w;
                if (target == targetRoot) {
                    Vector3f translation = sourceMatrix(sourceWorld, sourceMotion).getTranslation(new Vector3f())
                        .sub(sourceMotionReference).mul(unitScale);
                    qps[offset] = translation.x;
                    qps[offset + 1] = translation.y;
                    qps[offset + 2] = translation.z;
                }
            }
            api.SetBoneAdditiveTransformBatch(handle, targetBones, qps);
            api.EvaluateSkeleton(handle);
            evaluations++;
        }
    }

    public synchronized void apply(float[] sourceWorld) {
        apply(sourceWorld,0);
    }

    public synchronized void apply(float[] sourceWorld,float seconds) {
        applyBody(sourceWorld);
        finishPose(seconds);
    }

    public synchronized void configureFingerPreview(int[] roots,int[] children,float[] fractions) {
        fingerPreview=roots.length==0?null:new ForwardFingerPose(targetBones,parents,roots,children,fractions);
        fingerPreviewWeight=0;
    }
    public synchronized void setFingerPreviewWeight(float weight) {
        if(!Float.isFinite(weight)||weight<0||weight>1)throw new IllegalArgumentException("Invalid finger preview weight");
        if(weight>0&&fingerPreview==null)throw new IllegalStateException("Finger preview chains unavailable");
        fingerPreviewWeight=weight;
    }
    private void finishPose(float seconds) {
        if(fingerPreview!=null&&fingerPreviewWeight>0)fingerPreview.publish(api,handle,qps,neutralQps,fingerPreviewWeight);
        physics.finish(seconds);
    }

    private int targetIndex(int bone) {
        for (int i = 0; i < targetBones.length; i++) if (targetBones[i] == bone) return i;
        return -1;
    }

    private void multiplyLocalCorrection(int bone, Quaternionf correction) {
        int offset = targetIndex(bone) * 10;
        Quaternionf current = new Quaternionf(qps[offset+3],qps[offset+4],-qps[offset+5],-qps[offset+6]);
        current.mul(correction).normalize();
        qps[offset+3]=current.x; qps[offset+4]=current.y;
        qps[offset+5]=-current.z; qps[offset+6]=-current.w;
    }

    public record GripResult(int status, float targetX, float targetY, float targetZ,
                             float solvedX, float solvedY, float solvedZ) {}

    /** Calibrate the grip point from knuckle roots instead of treating the
     * wrist joint as the palm. One game combined finger root corresponds to
     * the centroid of the four PMX finger roots; no per-item offset is stored.
     */
    public synchronized void calibratePalms(int[] sourceHands, int[] sourceKnuckles,
            int[] targetHands, int[] targetKnuckles) {
        if (sourceHands.length!=2 || sourceKnuckles.length!=2 || targetHands.length!=2 || targetKnuckles.length!=8)
            throw new IllegalArgumentException("Expected two palms and four target finger roots per palm");
        for (int side=0;side<2;side++) {
            palmSourceHands[side]=sourceHands[side];palmTargetHands[side]=targetHands[side];
            Vector3f sourcePoint=sourceReference[sourceKnuckles[side]].getTranslation(new Vector3f());
            sourcePalmLocal[side]=new Matrix4f(sourceReference[sourceHands[side]]).invert().transformPosition(sourcePoint);
            Vector3f targetPoint=new Vector3f();
            for (int k=0;k<4;k++) targetPoint.add(targetReference[targetIndex(targetKnuckles[side*4+k])].getTranslation(new Vector3f()));
            targetPoint.mul(.25f);
            targetPalmLocal[side]=new Matrix4f(targetReference[targetIndex(targetHands[side])]).invert().transformPosition(targetPoint);
        }
    }

    /** Ozz requires a preferred local bend axis. The source T-pose has straight
     * arms, so derive it once from an existing bent game reference pose, just as
     * the upstream test derives mid_axis from its bent chain. This is profile
     * calibration data, never a per-action offset or an inferred world axis.
     */
    public synchronized float[] calibrateGripAxes(int[] targetArms) {
        if (targetArms.length!=6) throw new IllegalArgumentException("Expected two arm chains");
        readPalette();
        float[] result=new float[6];
        for (int side=0;side<2;side++) {
            int at=side*3;
            Matrix4f start=joint(targetArms[at]),mid=joint(targetArms[at+1]),end=joint(targetArms[at+2]);
            Vector3f bend=start.getTranslation(new Vector3f()).sub(mid.getTranslation(new Vector3f()))
                .cross(end.getTranslation(new Vector3f()).sub(mid.getTranslation(new Vector3f())));
            if (bend.lengthSquared()<1e-10f) throw new IllegalArgumentException("Bend reference is straight");
            mid.getUnnormalizedRotation(new Quaternionf()).normalize().invert().transform(bend);
            gripAxes[side]=bend.normalize();
            result[at]=bend.x;result[at+1]=bend.y;result[at+2]=bend.z;
        }
        return result;
    }

    /** Targets come from the actual source wrist pair around the shared shoulder
     * anchor. The common model unit conversion preserves game item size;
     * unreachable targets remain the mature solver's reachability result.
     * Ozz outputs local corrections; wrist orientation is restored afterward.
     */
    public synchronized GripResult[] applyWithGrip(float[] sourceWorld, OzzGrip solver,
            int[] sourceArms, int[] targetArms, float soften) {
        return applyWithGrip(sourceWorld,solver,sourceArms,targetArms,soften,0);
    }

    public synchronized GripResult[] applyWithGrip(float[] sourceWorld, OzzGrip solver,
            int[] sourceArms, int[] targetArms, float soften,float seconds) {
        return applyWithGrip(sourceWorld,solver,sourceArms,targetArms,soften,seconds,-1,-1);
    }
    public synchronized GripResult[] applyWithGrip(float[] sourceWorld, OzzGrip solver,
            int[] sourceArms, int[] targetArms, float soften,float seconds,int sourceAnchorBone,int targetAnchorBone) {
        return applyWithGrip(sourceWorld,solver,sourceArms,targetArms,soften,seconds,sourceAnchorBone,targetAnchorBone,null);
    }

    public static final class GripAdjustment {
        private final Matrix4f weapon;
        private final float[] offsets;
        private final float[] wristAngles;
        final String itemKey;
        private final int heldSide;
        float[] itemTuning;
        private Matrix4f correction;

        public GripAdjustment(Matrix4f weapon,float[] offsets,String itemKey) {
            this(weapon,offsets,new float[6],itemKey);
        }

        public GripAdjustment(Matrix4f weapon,float[] offsets,float[] wristAngles,String itemKey) {
            this(weapon,offsets,wristAngles,itemKey,1);
        }

        public GripAdjustment(Matrix4f weapon,float[] offsets,float[] wristAngles,String itemKey,int heldSide) {
            if(offsets.length!=6||wristAngles.length!=6||!weapon.isFinite()||Math.abs(weapon.determinant())<1e-8f)
                throw new IllegalArgumentException("Invalid grip calibration input");
            this.weapon=new Matrix4f(weapon);
            this.offsets=offsets.clone();
            this.wristAngles=wristAngles.clone();
            this.itemKey=itemKey;
            this.heldSide=heldSide;
            for(float value:offsets)if(!Float.isFinite(value)||Math.abs(value)>.12f)
                throw new IllegalArgumentException("Invalid grip offset");
            for(float value:wristAngles)if(!Float.isFinite(value)||Math.abs(value)>(float)(Math.PI/2))
                throw new IllegalArgumentException("Invalid wrist angle");
        }

        public int heldSide() { return heldSide; }

        public GripAdjustment wristsOnly() {
            for(float angle:wristAngles)if(angle!=0)return new GripAdjustment(weapon,new float[6],wristAngles,itemKey,heldSide);
            return null;
        }

        public Matrix4f placement(Matrix4f sourceItem) {
            if(correction==null)return null;
            Matrix4f placed=new Matrix4f(correction).mul(sourceItem);
            return itemTuning==null?placed:placed.mul(new Matrix4f().set(itemTuning));
        }
    }

    public synchronized GripResult[] applyWithGrip(float[] sourceWorld, OzzGrip solver,
            int[] sourceArms, int[] targetArms, float soften,float seconds,int sourceAnchorBone,int targetAnchorBone,
            GripAdjustment adjustment) {
        if (sourceArms.length!=6 || targetArms.length!=6)
            throw new IllegalArgumentException("Expected two shoulder/elbow/wrist chains");
        applyBody(sourceWorld);
        readPalette();
        Vector3f sourceAnchor = sourceMatrix(sourceWorld,sourceArms[0]).getTranslation(new Vector3f())
            .add(sourceMatrix(sourceWorld,sourceArms[3]).getTranslation(new Vector3f())).mul(.5f);
        Vector3f targetAnchor = joint(targetArms[0]).getTranslation(new Vector3f())
            .add(joint(targetArms[3]).getTranslation(new Vector3f())).mul(.5f);
        // First-person hands share the camera's head anchor.
        if(sourceAnchorBone>=0&&targetAnchorBone>=0) {
            sourceAnchor=sourceMatrix(sourceWorld,sourceAnchorBone).getTranslation(new Vector3f());
            targetAnchor=joint(targetAnchorBone).getTranslation(new Vector3f());
        }
        Vector3f[] wanted = new Vector3f[2];
        Quaternionf[] wristRotation = new Quaternionf[2];
        int[] status = new int[2];
        float scale = unitScale;
        for (int side=0;side<2;side++) {
            int at=side*3;
            if (sourcePalmLocal[side]==null) throw new IllegalStateException("Palm calibration missing");
            wanted[side] = sourceMatrix(sourceWorld,sourceArms[at+2]).transformPosition(new Vector3f(sourcePalmLocal[side])).sub(sourceAnchor);
            wanted[side].z = -wanted[side].z;
            wanted[side].mul(scale).add(targetAnchor);
            wristRotation[side]=joint(targetArms[at+2]).getUnnormalizedRotation(new Quaternionf()).normalize();
            wanted[side].sub(wristRotation[side].transform(new Vector3f(targetPalmLocal[side])));
            status[side]=solveGrip(sourceWorld,solver,sourceArms,targetArms,soften,side,wanted[side]);
        }
        restoreWrists(targetArms,wristRotation,status);
        if(adjustment!=null)adjustGrip(sourceWorld,solver,sourceArms,targetArms,soften,adjustment,wanted,status);
        finishPose(seconds);
        readPalette();
        GripResult[] results=new GripResult[2];
        for (int side=0;side<2;side++) {
            Vector3f solved=joint(targetArms[side*3+2]).getTranslation(new Vector3f());
            results[side]=new GripResult(status[side],wanted[side].x,wanted[side].y,wanted[side].z,
                solved.x,solved.y,solved.z);
        }
        return results;
    }

    private int solveGrip(float[] sourceWorld,OzzGrip solver,int[] sourceArms,int[] targetArms,float soften,int side,Vector3f wanted) {
        Vector3f bend=gripAxes[side];
        if(bend==null)return -3;
        int at=side*3;
        Vector3f pole=sourceMatrix(sourceWorld,sourceArms[at+1]).getTranslation(new Vector3f())
            .sub(sourceMatrix(sourceWorld,sourceArms[at]).getTranslation(new Vector3f()));
        pole.z=-pole.z;
        float[] matrices=new float[48],result=new float[8];
        for(int j=0;j<3;j++)joint(targetArms[at+j]).get(matrices,j*16);
        int status=solver.solve(matrices,new float[]{wanted.x,wanted.y,wanted.z},
            new float[]{pole.x,pole.y,pole.z},new float[]{bend.x,bend.y,bend.z},1,soften,0,result);
        if(status>=0){
            multiplyLocalCorrection(targetArms[at],new Quaternionf(result[0],result[1],result[2],result[3]));
            multiplyLocalCorrection(targetArms[at+1],new Quaternionf(result[4],result[5],result[6],result[7]));
        }
        return status;
    }

    private void restoreWrists(int[] targetArms,Quaternionf[] wristRotation,int[] status) {
        api.SetBoneAdditiveTransformBatch(handle,targetBones,qps);
        api.EvaluateSkeleton(handle); evaluations++;
        readPalette();
        for (int side=0;side<2;side++) {
            if (status[side]<0||wristRotation[side]==null) continue;
            int wrist=targetArms[side*3+2], offset=targetIndex(wrist)*10;
            Quaternionf local=joint(parents[wrist]).getUnnormalizedRotation(new Quaternionf()).normalize()
                .invert().mul(wristRotation[side]).normalize();
            qps[offset+3]=local.x; qps[offset+4]=local.y;
            qps[offset+5]=-local.z; qps[offset+6]=-local.w;
        }
        api.SetBoneAdditiveTransformBatch(handle,targetBones,qps);
    }

    private void adjustGrip(float[] sourceWorld,OzzGrip solver,int[] sourceArms,int[] targetArms,float soften,
            GripAdjustment adjustment,Vector3f[] wanted,int[] status) {
        // Freeze the normal gun placement before changing either hand.
        api.EvaluateSkeleton(handle);evaluations++;
        Matrix4f weapon=new Matrix4f().set(remapHeldItem(sourceWorld,palmSourceHands[adjustment.heldSide],palmTargetHands[adjustment.heldSide],
            adjustment.weapon.get(new float[16])));
        adjustment.correction=new Matrix4f(weapon).mul(new Matrix4f(adjustment.weapon).invert());
        if(adjustment.itemTuning!=null)weapon.mul(new Matrix4f().set(adjustment.itemTuning));
        Vector3f x=weapon.transformDirection(new Vector3f(1,0,0)).normalize();
        Vector3f y=weapon.transformDirection(new Vector3f(0,1,0)).normalize();
        Vector3f z=weapon.transformDirection(new Vector3f(0,0,1)).normalize();
        Quaternionf[] rotations=new Quaternionf[2];
        for(int side=0;side<2;side++){
            int at=side*3;
            float dx=adjustment.offsets[at],dy=adjustment.offsets[at+1],dz=adjustment.offsets[at+2];
            float rx=adjustment.wristAngles[at],ry=adjustment.wristAngles[at+1],rz=adjustment.wristAngles[at+2];
            boolean rotate=rx!=0||ry!=0||rz!=0;
            if(dx==0&&dy==0&&dz==0&&!rotate)continue;
            Matrix4f wrist=joint(targetArms[at+2]);
            wanted[side]=wrist.getTranslation(new Vector3f())
                .fma(dx*unitScale,x).fma(dy*unitScale,y).fma(dz*unitScale,z);
            rotations[side]=wrist.getUnnormalizedRotation(new Quaternionf()).normalize();
            if(rotate){
                // Keep the hand's contact point while changing the wrist's local orientation.
                Vector3f pivot=rotations[side].transform(new Vector3f(targetPalmLocal[side]));
                rotations[side].rotateXYZ(rx,ry,rz).normalize();
                wanted[side].add(pivot).sub(rotations[side].transform(new Vector3f(targetPalmLocal[side])));
            }
            status[side]=solveGrip(sourceWorld,solver,sourceArms,targetArms,soften,side,wanted[side]);
        }
        restoreWrists(targetArms,rotations,status);
    }

    /** Standard attachment remap; the game's item matrix already contains
     * its script/self attachments. There are no item-specific offsets.
     */
    public synchronized float[] remapHeldItem(float[] sourceWorld, int sourceHand,
            int targetHand, float[] actualItemWorld) {
        if (actualItemWorld.length!=16) throw new IllegalArgumentException("Expected item matrix");
        int side=sourceHand==palmSourceHands[0] && targetHand==palmTargetHands[0] ? 0 :
            sourceHand==palmSourceHands[1] && targetHand==palmTargetHands[1] ? 1 : -1;
        if (side<0 || sourcePalmLocal[side]==null) throw new IllegalStateException("Held-item palm not calibrated");
        readPalette();
        Matrix4f calibration = new Matrix4f(targetReference[targetIndex(targetHand)]).setTranslation(0,0,0)
            .invert().mul(basis).mul(new Matrix4f(sourceReference[sourceHand]).setTranslation(0,0,0)).scale(unitScale);
        Matrix4f world = joint(targetHand).translate(targetPalmLocal[side]).mul(calibration)
            .mul(sourceMatrix(sourceWorld,sourceHand).translate(sourcePalmLocal[side]).invert())
            .mul(new Matrix4f().set(actualItemWorld));
        return world.get(new float[16]);
    }

    /** Retarget a non-hand attachment through its nearest mapped source
     * ancestor. The actual item matrix retains the unmapped child bone and
     * both game attachment transforms; no bone name or offset is rewritten.
     */
    public synchronized float[] remapBodyAttachment(float[] sourceWorld, int sourceAnchor,
            int targetAnchor, float[] actualItemWorld) {
        if (actualItemWorld.length!=16) throw new IllegalArgumentException("Expected item matrix");
        readPalette();
        Matrix4f calibration = new Matrix4f(targetReference[targetIndex(targetAnchor)]).setTranslation(0,0,0)
            .invert().mul(basis).mul(new Matrix4f(sourceReference[sourceAnchor]).setTranslation(0,0,0)).scale(unitScale);
        Matrix4f world = joint(targetAnchor).mul(calibration)
            .mul(sourceMatrix(sourceWorld,sourceAnchor).invert()).mul(new Matrix4f().set(actualItemWorld));
        return world.get(new float[16]);
    }

    public int animationEvaluations() { return evaluations; }
    public float[] lastTransforms() { return qps.clone(); }
    public synchronized void setPhysicsEnabled(boolean enabled) { physics.request(enabled); }
    public synchronized boolean physicsEnabled() { return physics.enabled(); }
    public synchronized void resetPhysics() { physics.reset(); }
    synchronized void finishNativePose(float seconds) { physics.finish(seconds); }
    synchronized void deferSkinning(boolean defer) { physics.deferSkinning(defer); }
    public synchronized void setModelMotion(float x,float y,float z,float yaw) { physics.motion(x,y,z,yaw); }
    public synchronized long advancingPhysicsFrames() { return physics.advancingFrames(); }
}
