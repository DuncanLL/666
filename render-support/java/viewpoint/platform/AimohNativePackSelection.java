package viewpoint.platform;
import java.io.*;
import java.util.*;

/** Owned preview selection through the actual Native inheritance/configuration path. */
public final class AimohNativePackSelection {
    public record DisplaySettings(float contrast,float saturation,float vibrance,float exposure,float strength,boolean filmic,float surfaceDetail,float skinGloss){}
    private static ShaderPacks.Active displayPack;
    private static float[] displayLive;
    private static DisplaySettings displaySettings;
    private static int contrastIndex,saturationIndex,vibranceIndex,exposureIndex,strengthIndex,detailIndex,glossIndex;
    private static boolean filmic;
    private static final java.util.regex.Pattern FILMIC=java.util.regex.Pattern.compile("(?m)^#define\\s+OPTION_TONE_CURVE\\s+0\\s*$");
    /** Native already keeps numeric options on the CPU. Never read the GPU option UBO per frame. */
    public static DisplaySettings displaySettings(){
        ShaderPacks.Active pack=ShaderPacks.drawn();float[] live=ShaderPacks.live();if(pack==null||live==null||ShaderPacks.active()!=pack)return null;
        if(displayPack!=pack){
            contrastIndex=saturationIndex=vibranceIndex=exposureIndex=strengthIndex=detailIndex=glossIndex=-1;int at=0;
            for(PackOption option:pack.pack.options)if(option.kind==PackOption.Kind.NUMBER){switch(option.id){case "contrast"->contrastIndex=at;case "saturation"->saturationIndex=at;case "vibrance"->vibranceIndex=at;case "exposure"->exposureIndex=at;case "characterDisplayStrength"->strengthIndex=at;case "characterSurfaceDetail"->detailIndex=at;case "characterSkinGloss"->glossIndex=at;}at++;}
            filmic=FILMIC.matcher(pack.defines).find();displayPack=pack;displayLive=null;
        }
        if(displayLive!=live){displayLive=live;displaySettings=contrastIndex<0||saturationIndex<0||vibranceIndex<0||exposureIndex<0?null:new DisplaySettings(live[contrastIndex],live[saturationIndex],live[vibranceIndex],live[exposureIndex],strengthIndex<0?1:live[strengthIndex],filmic,detailIndex<0?0:live[detailIndex],glossIndex<0?0:live[glossIndex]);}
        return displaySettings;
    }
    public static void clearDisplaySettings(){displayPack=null;displayLive=null;displaySettings=null;}
    public static String readData(String path){return GlProgram.pack().pack.shader(path);}
    public static String expanded(String path){return Shaders.load(path,GlProgram.pack()).text;}
    public static viewpoint.iris.SourceFiles files(ShaderPacks.Active active){return path->active.pack.shader(path);}
    public static GlProgram compute(String name,String path){return GlProgram.compute(name,expanded(path),List.of());}
    public static void use(String folder)throws IOException{
        Map<String,ShaderPack> parents=new HashMap<>();
        ShaderPack library=ShaderPack.parse("library",ShaderPack.jar("library"),id->parents.get(id),true);parents.put("library",library);
        ShaderPack defaults=ShaderPack.parse("default",ShaderPack.jar("default"),id->parents.get(id),true);parents.put("default",defaults);
        ShaderPack selected=ShaderPack.parse("gf2-native-development",ShaderPack.folder(new File(folder)),id->parents.get(id),false);
        PackPreset preset=selected.preset(selected.firstPreset);ShaderPacks.use(selected,preset::valueFor);
    }
}
