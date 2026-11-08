package viewpoint.platform;

/** Owned controlled fixture: exercise the actual CPU live-option API without replacing VP classes. */
public final class NativeDisplaySettingsProbe {
    public static void liveMode(){
        mode(true);
    }
    public static void mode(boolean live){
        ShaderPack pack=ShaderPacks.drawn().pack;PackPreset preset=pack.preset("ultra");java.util.Map<String,String> values=new java.util.HashMap<>();int index=0;
        for(PackOption option:pack.options)if(option.kind==PackOption.Kind.NUMBER)values.put(option.id,Float.toString(ShaderPacks.live()[index++]));
        ShaderPacks.use(pack,option->values.getOrDefault(option.id,preset.valueFor(option)),live);var active=ShaderPacks.active();String error=GlProgram.relinkAll(active);
        if(error!=null)throw new IllegalStateException(error);ShaderPacks.linked(active);
    }
    public static void strength(float value){
        ShaderPack pack=ShaderPacks.drawn().pack;java.util.Map<String,String> values=new java.util.HashMap<>();int index=0;for(PackOption option:pack.options)if(option.kind==PackOption.Kind.NUMBER)values.put(option.id,Float.toString(ShaderPacks.live()[index++]));
        ShaderPacks.values(pack,option->option.id.equals("characterDisplayStrength")?Float.toString(value):values.get(option.id));
    }
    public static void numbers(float contrast,float saturation,float vibrance,float exposure){
        ShaderPack pack=ShaderPacks.drawn().pack;PackPreset preset=pack.preset("ultra");
        java.util.Map<String,String> values=new java.util.HashMap<>();int index=0;for(PackOption option:pack.options)if(option.kind==PackOption.Kind.NUMBER)values.put(option.id,Float.toString(ShaderPacks.live()[index++]));
        ShaderPacks.values(pack,option->switch(option.id){case "contrast"->Float.toString(contrast);case "saturation"->Float.toString(saturation);case "vibrance"->Float.toString(vibrance);case "exposure"->Float.toString(exposure);default->values.getOrDefault(option.id,preset.valueFor(option));});
    }
}
