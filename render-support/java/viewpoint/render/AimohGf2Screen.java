package viewpoint.render;

import java.util.Map;

/** The selected Eclipse pack's screen transport, shared by source geometry and native glass. */
public final class AimohGf2Screen {
    public static float scale(Map<String,String> options){
        if(!"true".equals(options.get("TAA"))||!"true".equals(options.get("TAA_UPSCALING")))return 1;
        float value=Float.parseFloat(options.get("SCALE_FACTOR"));
        if(!Float.isFinite(value)||value<=0||value>1)throw new IllegalArgumentException("Invalid Eclipse render scale");
        return value;
    }
    public static String glsl(){return """
        vec4 aimohEclipseClip(vec4 clip,vec2 jitter,float scale){
            clip.xy=clip.xy*scale+scale*clip.w-clip.w;
            clip.xy+=jitter*clip.w;
            return clip;
        }
        """;}
    public static void inputs(Map<String,float[]> values,int width,int height,float scale){
        float w=width*scale,h=height*scale;
        float[] screen={w,h,1+1/w,1+1/h};
        values.put("_ScreenParams",screen);values.put("_ScaledScreenParams",screen);
    }
}
