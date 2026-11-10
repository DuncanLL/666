package viewpoint.platform;
import java.io.*;
import java.util.*;

/** Owned preview selection through the actual Native inheritance/configuration path. */
public final class AimohNativePackSelection {
    public record DisplaySettings(float contrast,float saturation,float vibrance,float exposure,float strength,boolean filmic,float surfaceDetail,float skinGloss,float outlineWidth){}
    private static ShaderPacks.Active displayPack;
    private static DisplaySettings displaySettings;
    private static final java.util.regex.Pattern FILMIC=java.util.regex.Pattern.compile("(?m)^#define\\s+OPTION_TONE_CURVE\\s+0\\s*$");
    private static final java.util.regex.Pattern NUMBER=java.util.regex.Pattern.compile("(?m)^#define[ \\t]+OPTION_([A-Z_]+)[ \\t]+([-+0-9.Ee]+)[ \\t]*$");
    /** This Workshop host compiles numeric options into the selected pack's defines. */
    public static DisplaySettings displaySettings(){
        ShaderPacks.Active pack=ShaderPacks.drawn();if(pack==null||ShaderPacks.active()!=pack)return null;
        if(displayPack!=pack){
            Map<String,Float> values=new HashMap<>();
            var matches=NUMBER.matcher(pack.defines);
            while(matches.find())try{values.put(matches.group(1),Float.parseFloat(matches.group(2)));}catch(NumberFormatException ignored){}
            Float contrast=values.get("CONTRAST"),saturation=values.get("SATURATION"),
                vibrance=values.get("VIBRANCE"),exposure=values.get("EXPOSURE");
            displaySettings=contrast==null||saturation==null||vibrance==null||exposure==null?null:
                new DisplaySettings(contrast,saturation,vibrance,exposure,values.getOrDefault("CHARACTER_DISPLAY_STRENGTH",1f),
                    FILMIC.matcher(pack.defines).find(),values.getOrDefault("CHARACTER_SURFACE_DETAIL",0f),
                    values.getOrDefault("CHARACTER_SKIN_GLOSS",0f),values.getOrDefault("CHARACTER_OUTLINE_WIDTH",1f));
            displayPack=pack;
        }
        return displaySettings;
    }
    public static void clearDisplaySettings(){displayPack=null;displaySettings=null;}
    public static String readData(String path){return GlProgram.pack().pack.shader(path);}
    public static String expanded(String path){return Shaders.load(path,GlProgram.pack()).text;}
    public static viewpoint.iris.SourceFiles files(ShaderPacks.Active active){return path->active.pack.shader(path);}
    public static GlProgram compute(String name,String path){return GlProgram.compute(name,expanded(path),List.of());}
    public static void use(String folder)throws IOException{
        Map<String,ShaderPack> parents=new HashMap<>();
        ShaderPack library=ShaderPack.parse("library",ShaderPack.jar("library"),id->parents.get(id),true);parents.put("library",library);
        ShaderPack vivid=ShaderPack.parse("vivid",ShaderPack.jar("vivid"),id->parents.get(id),true);parents.put("vivid",vivid);
        ShaderPack defaults=ShaderPack.parse("default",ShaderPack.jar("default"),id->parents.get(id),true);parents.put("default",defaults);
        ShaderPack selected=ShaderPack.parse("gf2-native-development",ShaderPack.folder(new File(folder)),id->parents.get(id),false);
        PackPreset preset=selected.preset(selected.firstPreset);ShaderPacks.use(selected,preset::valueFor);
    }
}
