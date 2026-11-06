package viewpoint.render;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import viewpoint.platform.GlProgram;
import viewpoint.platform.ShaderLibrary;

/** Read helpers from the installed official host. The addon ships no native shader copies. */
public final class AimohGf2NativeShaders {
    private static final Pattern INCLUDE=Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"\\s*$");
    private static String replace(String source,String before,String after){
        int at=source.indexOf(before);
        if(at<0||source.indexOf(before,at+before.length())>=0)throw new IllegalStateException("Official shader ABI changed: "+before);
        return source.substring(0,at)+after+source.substring(at+before.length());
    }
    public static String heldLight(String source){
        if(source==null)return null;
        source=replace(source,"uniform int uTorchCount;\nuniform vec4 uTorchPos[8];\nuniform vec4 uTorchDir[8];\nuniform vec3 uTorchColor[8];","#include \"lib/torches.glsl\"");
        String term="uTorchColor[i] * beam * (0.35 + 0.65 * max(dot(n, -l), 0.0)) * (1.0 - dist / reach)";
        return replace(source,term,term+" * torchShadow(i,pos,n)");
    }
    public static String modelVertex(){
        try{
            Class<?> packs=Class.forName("viewpoint.platform.ShaderPacks"),active=Class.forName("viewpoint.platform.ShaderPacks$Active");
            var library=packs.getDeclaredMethod("library",int.class);library.setAccessible(true);
            var load=Class.forName("viewpoint.platform.Shaders").getDeclaredMethod("load",String.class,active);load.setAccessible(true);
            Object expanded=load.invoke(null,"iris/model_vertex.glsl",library.invoke(null,0));
            var text=expanded.getClass().getDeclaredField("text");text.setAccessible(true);
            return "#version 430 compatibility\n"+((String)text.get(expanded)).replaceAll("(?m)^#version[^\n]*\n","")+"\n";
        }catch(ReflectiveOperationException problem){throw new IllegalStateException("Official model shader input unavailable",problem);}
    }
    private static String raw(String name){
        String source=ShaderLibrary.read(name);
        if(source==null)throw new IllegalStateException("Official shader missing: "+name);
        if(name.equals("lib/glass_water_light.glsl"))source=replace(source,"  return sum;","  return sum * 0.1; // Eclipse HDR transport scale.");
        return source;
    }
    private static String expand(String source,Set<String> chain){
        Matcher match=INCLUDE.matcher(source);StringBuffer expanded=new StringBuffer();
        while(match.find()){
            String name=match.group(1);if(!chain.add(name))throw new IllegalStateException("Official shader include cycle: "+name);
            String body=expand(raw(name),chain);chain.remove(name);match.appendReplacement(expanded,Matcher.quoteReplacement(body));
        }
        return match.appendTail(expanded).toString();
    }
    public static GlProgram glass(){
        var sources=glassSources();
        return GlProgram.fromSources("Eclipse native glass rain",new GlProgram.Sources(sources[0],null,null,null,sources[1]),Map.of(),Map.of(),List.of());
    }
    public static String[] glassSources(){
        String vertex=replace(raw("model.vert"),"void main() {","uniform vec2 uEclipseGlassJitter;\nvoid vpNativeGlassVertex() {");
        vertex+="\nuniform float uEclipseGlassScale=1.0;\n"+AimohGf2Screen.glsl()+"\nvoid main(){vpNativeGlassVertex();gl_Position=aimohEclipseClip(gl_Position,uEclipseGlassJitter,uEclipseGlassScale);}\n";
        String fragment=raw("vehicle_glass.frag");int main=fragment.indexOf("void main() {");
        if(main<0)throw new IllegalStateException("Official glass shader entry missing");fragment=fragment.substring(0,main);
        fragment=replace(fragment,"#version 330","#version 430 compatibility");
        fragment=replace(fragment,"#include \"lib/translucent.glsl\"","");
        fragment+="""
            layout(location=0)out vec4 eclipseRainColour;
            void main(){
              carDx=dFdx(vCar);carDy=dFdy(vCar);
              Vehicle v=vehicle();if(v.window<=0.0||v.gone>0.5)discard;
              cameraClear(vPos,vClear);
              vec3 n=litSide(normalize(vNormal),facePlane(vPos),vPos,uEye);
              vec4 light=vec4(vLight.rgb*0.1,vLight.a>=1.0?1.0:0.0);
              float lit=sunlit(vPos,n,uEye,false);
              vec4 base=texelFetch(uGlassScene,ivec2(gl_FragCoord.xy),0);
              vec4 wet=wiped(carWater(base.rgb,0.0,normalize(vNormal),light,lit),n,light,lit);
              eclipseRainColour=vec4(wet.rgb,base.a);
            }
            """;
        String defines="#extension GL_ARB_shader_storage_buffer_object : enable\n#extension GL_ARB_shader_image_load_store : enable\n";
        for(var entry:ShaderLibrary.pipelineMacros().entrySet())defines+="#define "+entry.getKey()+" "+entry.getValue()+"\n";
        String[] result={vertex,fragment};
        for(int i=0;i<result.length;i++){
            String body=expand(result[i],new HashSet<>());int line=body.indexOf('\n');result[i]=body.substring(0,line+1)+defines+body.substring(line+1);
        }
        return result;
    }
}
