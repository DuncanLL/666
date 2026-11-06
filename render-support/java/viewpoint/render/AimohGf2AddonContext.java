package viewpoint.render;

import java.util.*;
import org.json.JSONObject;
import viewpoint.iris.IrisPack;
import viewpoint.platform.IrisPacks;

/** Common addon activation. No character identity appears in this decision. */
public final class AimohGf2AddonContext {
    private static final Map<IrisPack,Boolean> supported=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<ArrayDeque<Boolean>> compiling=ThreadLocal.withInitial(ArrayDeque::new);
    public static boolean supports(IrisPack pack){
        if(pack==null)return false;
        return supported.computeIfAbsent(pack,key->{
            try{
                if(!key.source.exists("gf2/manifest.json"))return false;
                JSONObject manifest=new JSONObject(key.source.read("gf2/manifest.json"));
                JSONObject addon=manifest.optJSONObject("addon_runtime");
                return "gf2-original-eclipse".equals(manifest.optString("pipeline"))&&addon!=null
                    &&"AimohGF2Eclipse".equals(addon.optString("id"))&&addon.optInt("format")==1;
            }catch(Exception problem){throw new IllegalArgumentException("GF2 addon bootstrap unreadable",problem);}
        });
    }
    public static boolean active(){var drawn=IrisPacks.drawn();return drawn!=null&&supports(drawn.pack())&&!"false".equals(drawn.values().get("VP_DANRP_PIPELINE"));}
    public static void beginCompile(IrisPack pack){compiling.get().push(supports(pack));}
    public static void endCompile(){var stack=compiling.get();if(!stack.isEmpty())stack.pop();if(stack.isEmpty())compiling.remove();}
    public static boolean compiling(){var stack=compiling.get();return !stack.isEmpty()&&stack.peek();}
    public static Map<String,String> macros(IrisPack pack,Map<String,String> original){return supports(pack)?AimohGF2Services.macros(original):original;}
    public static int firstUserUnit(String name,viewpoint.platform.GlProgram program){return compiling()?AimohGF2Services.firstUserUnit(name,program):96;}
    public static int nextUserUnit(String name,int next){return compiling()?AimohGF2Services.nextUserUnit(name,next):next;}
    public static String fragment(String source,String name){return compiling()?AimohGf2IrisGlass.fragment(AimohGF2Services.fragmentSource(source,name)):source;}
    public static void bindMuzzle(IrisProgram program,ShadowPass shadows){if(active())shadows.muzzle.uniforms(program.gl);}
    public static int texture(IrisTargets targets,String name){return active()?AimohGF2Services.texture(targets,name):-1;}
    public static void nativeLights(IrisTargets targets,IrisProgram program){if(active())AimohGF2Services.nativeLights(targets,program);}
    public static String shaderSource(String source,String path){return compiling()&&"iris/held_light.glsl".equals(path)?AimohGf2NativeShaders.heldLight(source):source;}
}
