package aimoh.pmxpreview;

import aimoh.motion.PmxDrawMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import viewpoint.render.ModelDraws;
import zombie.asset.AssetPath;
import zombie.core.skinnedmodel.model.ModelMesh;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;

public final class GripPhaseCheck {
    private static final float[] X = {-1f, 0f, 1f};
    private static final float[] YAW = {-0.3f, 0.1f, 0.85f};
    private static final Vector3f HANDLE = new Vector3f(0.35f, 0.15f, 0.24f);
    private static final float TOLERANCE = 0.00002f;
    private static Instrumentation instrumentation;

    public static void premain(String args, Instrumentation agent) { instrumentation = agent; }

    public static void main(String[] args) throws Exception {
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        field(loader,"g_instrumentation").set(null,instrumentation);
        me.zed_0xff.zombie_buddy.PatchEngine.applyPatches("aimoh.pmxpreview",GripPhaseCheck.class.getClassLoader());

        Class<?> type = Class.forName("viewpoint.render.ModelBlend");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method draw = type.getDeclaredMethod("drawn",ModelDraws.class,long.class,
                float.class,float.class,float.class,float.class);
        draw.setAccessible(true);

        float largest = 0;
        int cases = 0;
        for (boolean adjusted : new boolean[]{false,true})
            for (boolean cut : new boolean[]{false,true})
                for (float phase : new float[]{-1.25f,-0.75f,-0.25f,0f,0.25f,0.65f,1f,1.2f}) {
                    Object blend = constructor.newInstance();
                    largest = Math.max(largest,replay(blend,draw,phase,cut,adjusted,false));
                    cases++;
                }
        largest = Math.max(largest,replay(constructor.newInstance(),draw,-.75f,false,false,true));
        cases++;
        System.out.printf(Locale.ROOT,
                "GRIP_PHASE_PASS cases=%d max_palm_item_error=%.8f actual_host_hook=true%n",cases,largest);
    }

    private static float replay(Object blend,Method host,float phase,boolean cut,boolean adjusted,boolean swap) throws Exception {
        var unsafe = field(sun.misc.Unsafe.class,"theUnsafe").get(null);
        ModelSession session = (ModelSession)((sun.misc.Unsafe)unsafe).allocateInstance(ModelSession.class);
        ViewpointModel backend = new ViewpointModel(session);
        ModelMesh itemMesh = new ModelMesh(new AssetPath("test-grip"),null,new ModelMesh.MeshAssetParams());
        var presentation = new PmxPresentation();
        Bridge.Frozen frozen = null;
        ModelDraws previous = null;
        for (int frame=0;frame<3;frame++) {
            Matrix4f local = new Matrix4f().translation(X[frame],0,0).rotateY(YAW[frame]);
            if (adjusted) local.translate(.12f,-.07f,.05f).rotateZ(.18f);
            Vector3f palm = local.transformPosition(new Vector3f(HANDLE));
            var bodyFrame = new PmxDrawMesh.FrameData(new float[]{palm.x,palm.y,palm.z},
                    new float[]{0f,1f,0f},new float[0]);
            session.olderFrozen = frozen == null ? null : frozen.previous;
            session.previousFrozen = frozen == null ? null : frozen.data;
            ModelDraws source = new ModelDraws();
            Matrix4f actor = new Matrix4f().translation(frame*.15f,0,0).rotateY(frame*.12f);
            int gearIndex = source.add(itemMesh,null,null,new Matrix4f(actor).mul(local),-1,-1,0,3);
            int bodyIndex = source.add(null,null,null,actor,-1,-1,0,3);
            source.key(bodyIndex,"character");
            source.key(gearIndex,"held-item");
            if (previous != null) {
                source.motion(gearIndex,previous,gearIndex,0,false);
                source.motion(bodyIndex,previous,bodyIndex,0,false);
            }
            DrawAccess.ownPlacement(source,gearIndex,bodyIndex,previous!=null);
            frozen = new Bridge.Frozen(session,null,bodyFrame,source,0,bodyIndex);
            backend.retainHeldFrames(bodyFrame,frozen.previous,frozen.older);
            String itemKey = swap&&frame==2 ? "new-item" : "same-item";
            frozen.addHeld(backend.captureHeld(frozen,gearIndex,null,new DrawAccess.Gear(null,1,itemKey),itemMesh,() -> local));
            if (frame<2) {
                host.invoke(blend,source,(long)frame+1,0f,0f,0f,1f);
                previous = source;
                continue;
            }

            frozen.timing = PmxPresentation.timing(bodyFrame,frozen.previous,frozen.older,phase,cut);
            var sampled = presentation.sample(bodyFrame,frozen.timing,cut);
            field(Bridge.class,"preparedDraws").set(null,source);
            @SuppressWarnings("unchecked")
            var ready = (ArrayList<Bridge.Frozen>)field(Bridge.class,"preparedHeld").get(null);
            ready.add(frozen);

            ModelDraws drawn = (ModelDraws)host.invoke(blend,source,3L,0f,0f,0f,phase);
            if (!ready.isEmpty()) throw new AssertionError("Stock host did not execute the presentation hook");
            float[] p = swap ? bodyFrame.positions() : sampled.positions();
            Vector3f expected = DrawAccess.matrix(drawn,bodyIndex).transformPosition(new Vector3f(p[0],p[1],p[2]));
            Vector3f actual = DrawAccess.matrix(drawn,gearIndex).transformPosition(new Vector3f(HANDLE));
            float error = actual.distance(expected);
            if (!(error<TOLERANCE)) throw new AssertionError("phase="+phase+" cut="+cut+" calibrated="+adjusted+" error="+error);
            return error;
        }
        throw new AssertionError("Missing final draw");
    }

    private static Field field(Class<?> type,String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
