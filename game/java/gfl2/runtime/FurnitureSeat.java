package gfl2.runtime;

import com.shiroha.mmdskin.NativeFunc;
import gfl2.motion.PmxDrawMesh;
import gfl2.motion.PmxModel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.joml.Vector3f;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.system.MemoryUtil;

/** Measure the posed hip surface in model space, independently of the furniture's world height. */
final class FurnitureSeat {
    private final int left, right, leftKnee, rightKnee, leftTarget, rightTarget;
    private int[] vertices;
    private float[] heights;
    private float seatedSurface = Float.NaN;
    float blend;

    boolean needsMeasurement() { return Float.isNaN(seatedSurface); }

    FurnitureSeat(JSONObject profile) {
        var names = profile.getJSONArray("source_bone_names").toList();
        left = names.indexOf("Bip01_L_Thigh");
        right = names.indexOf("Bip01_R_Thigh");
        leftKnee = names.indexOf("Bip01_L_Calf");
        rightKnee = names.indexOf("Bip01_R_Calf");
        var source = profile.getJSONArray("source_ids").toList();
        var target = profile.getJSONArray("target_ids");
        leftTarget = target.optInt(source.indexOf(left), -1);
        rightTarget = target.optInt(source.indexOf(right), -1);
        if (left<0 || right<0 || leftKnee<0 || rightKnee<0 || leftTarget<0 || rightTarget<0)
            seatedSurface=Float.NEGATIVE_INFINITY;
    }

    private static float verticalFraction(float[] pose, int thigh, int calf) {
        int a = thigh * 16 + 12, b = calf * 16 + 12;
        float x = pose[b] - pose[a], y = pose[b+1] - pose[a+1], z = pose[b+2] - pose[a+2];
        float length = (float)Math.sqrt(x*x + y*y + z*z);
        return length > 0 ? Math.abs(y)/length : 1;
    }

    private int[] hipVertices(NativeFunc api, long handle, JSONObject profile) {
        int count = profile.getInt("vertices");
        var parents = profile.getJSONArray("parents");
        int leftParent = parents.getInt(leftTarget), rightParent = parents.getInt(rightTarget);
        boolean[] leftAncestors = new boolean[parents.length()];
        for (int i=leftParent; i>=0; i=parents.getInt(i)) leftAncestors[i]=true;
        int pelvis=rightParent;
        while (pelvis>=0 && !leftAncestors[pelvis]) pelvis=parents.getInt(pelvis);
        var rest = profile.getJSONArray("initial_positions");
        boolean[] hip = new boolean[parents.length()];
        for (int i=0; i<hip.length; i++) {
            hip[i] = i == pelvis || i == leftParent || i == rightParent ||
                sameOrigin(rest,i,leftTarget) || sameOrigin(rest,i,rightTarget);
        }
        ByteBuffer scratch = MemoryUtil.memAlloc(Math.multiplyExact(count,32));
        try {
            ByteBuffer bones = scratch.slice(0,count*16).order(ByteOrder.nativeOrder());
            ByteBuffer weights = scratch.slice(count*16,count*16).order(ByteOrder.nativeOrder());
            if (api.CopyBoneIndicesToBuffer(handle,bones,count) != count ||
                    api.CopyBoneWeightsToBuffer(handle,weights,count) != count) return new int[0];
            int[] ids = new int[count];
            int found = 0;
            for (int i=0; i<count; i++) {
                float influence = 0;
                for (int k=0; k<4; k++) {
                    int at = i*16+k*4, bone = bones.getInt(at);
                    if (bone >= 0 && bone < hip.length && hip[bone]) influence += weights.getFloat(at);
                }
                if (influence > .65f) ids[found++] = i;
            }
            return Arrays.copyOf(ids,found);
        } finally {
            MemoryUtil.memFree(scratch);
        }
    }

    private static boolean sameOrigin(JSONArray rest, int a, int b) {
        for (int k=0; k<3; k++)
            if (Math.abs(rest.getDouble(a*3+k)-rest.getDouble(b*3+k)) > .0001) return false;
        return true;
    }

    float offset(NativeFunc api, PmxModel model, JSONObject profile, float scale, float[] pose,
                 PmxDrawMesh.FrameData frame, boolean settled, boolean female) {
        if (left < 0 || right < 0 || leftKnee < 0 || rightKnee < 0 || leftTarget < 0 || rightTarget < 0) return 0;
        float vertical = .5f*(verticalFraction(pose,left,leftKnee)+verticalFraction(pose,right,rightKnee));
        // Standing thighs are vertical. The game's seated idle has a vertical fraction of 0.17.
        blend = Math.clamp((1-vertical)/.8f,0,1);
        blend *= blend*(3-2*blend);
        if (blend == 0) return 0;
        float surface = seatedSurface;
        if (Float.isNaN(surface)) {
            if (vertices == null) {
                vertices = hipVertices(api,model.handle(),profile);
                heights = new float[vertices.length];
            }
            if (vertices.length == 0) {
                seatedSurface=Float.NEGATIVE_INFINITY;
                return 0;
            }
            float[] xyz = frame.positions();
            Vector3f center = model.retargeter().jointMidpoint(leftTarget,rightTarget);
            int used = 0;
            for (int id : vertices) {
                int i = id*3;
                float x = xyz[i], z = xyz[i+2];
                if (Math.abs(x-center.x) < .14f*scale && Math.abs(z-center.z) < .13f*scale)
                    heights[used++] = xyz[i+1];
            }
            if (used == 0) return 0;
            Arrays.sort(heights,0,used);
            float hipY = .5f*(pose[left*16+13]+pose[right*16+13]);
            surface = heights[(used-1)/50]/scale - hipY;
            if (settled && blend == 1) {
                seatedSurface = surface;
                vertices = null;
                heights = null;
            }
        }
        // Hip-to-seat distance measured on the two stock PZ skinned bodies in Bob_SatChair.
        return blend * Math.max(0,surface+(female ? .08780f : .09312f));
    }
}
