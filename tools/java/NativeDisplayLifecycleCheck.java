package viewpoint.render;
import java.lang.reflect.*;
import org.lwjgl.opengl.GL11;
import org.json.JSONObject;

/** Owned context only: verify the new shared LUT textures are actually released. */
public final class NativeDisplayLifecycleCheck {
    private static Object field(Object object,String name)throws Exception{Class<?> c=object instanceof Class<?> type?type:object.getClass();Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object instanceof Class<?>?null:object);}
    public static String run()throws Exception{
        Object renderer=field(AimohNativeGf2Runtime.class,"renderer"),display=field(renderer,"display");int[] names=((int[])field(display,"textures")).clone();
        if(names.length!=2||names[0]==0||names[1]==0)throw new AssertionError("Shared display tables were not allocated");
        for(int name:names)if(!GL11.glIsTexture(name))throw new AssertionError("Shared display texture was not live");
        AimohNativeGf2Runtime.close();for(int name:names)if(GL11.glIsTexture(name))throw new AssertionError("Shared display texture leaked after close");
        return new JSONObject().put("status","SHARED_DISPLAY_LUT_TEXTURES_RELEASED").put("textures",names).put("game_started",false).toString();
    }
}
