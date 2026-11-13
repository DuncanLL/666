package viewpoint.render;
import java.util.*;
import org.joml.*;

/** Actual Native camera -> existing source-register builder's camera-relative coordinate contract. */
public final class AimohNativeSourceSpace {
    public static Map<String,float[]> values(FrameContext frame,float[] nativeMainDirection,Map<String,float[]> previous){
        if(nativeMainDirection.length!=3)throw new IllegalArgumentException("Actual Native main direction required");
        Matrix4f rotation=new Matrix4f(frame.view).setTranslation(0,0,0).scale(-1,1,-1);
        Map<String,float[]> result=new LinkedHashMap<>();
        result.put("gbufferModelView",rotation.get(new float[16]));
        result.put("gbufferModelViewInverse",new Matrix4f(rotation).invert().get(new float[16]));
        // The source colour prototype uses the actual Native projection, including its jitter.
        // Do not add the old Iris clip jitter on top; motion-history conversion remains a separate gate.
        result.put("gbufferProjection",frame.jitteredProjection.get(new float[16]));
        boolean reset=previous==null||frame.scene.cameraCut;
        result.put("gbufferPreviousModelView",(reset?result:previous).get("gbufferModelView").clone());
        result.put("gbufferPreviousProjection",(reset?result:previous).get("gbufferProjection").clone());
        result.put("lightDir",new float[]{-nativeMainDirection[0],nativeMainDirection[1],-nativeMainDirection[2]});
        result.put("frameTimeCounter",new float[]{frame.time});
        return result;
    }
    private AimohNativeSourceSpace(){}
}
