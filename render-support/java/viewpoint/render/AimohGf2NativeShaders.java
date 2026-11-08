package viewpoint.render;
/** Read the official model helper from the loaded VP; the addon carries no VP shader copy. */
public final class AimohGf2NativeShaders {
    public static String modelVertex(){
        try{Class<?> packs=Class.forName("viewpoint.platform.ShaderPacks"),active=Class.forName("viewpoint.platform.ShaderPacks$Active");var library=packs.getDeclaredMethod("library",int.class);library.setAccessible(true);var load=Class.forName("viewpoint.platform.Shaders").getDeclaredMethod("load",String.class,active);load.setAccessible(true);Object expanded=load.invoke(null,"iris/model_vertex.glsl",library.invoke(null,0));var text=expanded.getClass().getDeclaredField("text");text.setAccessible(true);return "#version 430 compatibility\n"+((String)text.get(expanded)).replaceAll("(?m)^#version[^\n]*\n","")+"\n";}catch(ReflectiveOperationException e){throw new IllegalStateException("Official model shader input unavailable",e);}
    }
    private AimohGf2NativeShaders(){}
}
