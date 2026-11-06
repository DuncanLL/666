package viewpoint.render;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
/** Host-version ABI only; source character formulas and material values are untouched. */
public final class AimohGf2Rc2Vertex {
    private static final String NATIVE=AimohGf2NativeShaders.modelVertex();
    private static final Pattern HOST_OUTPUT=Pattern.compile("(?m)^(?:flat[ \\t]+)?out[ \\t]+(\\w+[ \\t]+\\w+;)[ \\t]*$");
    private static final Pattern OUTPUT_LOCATION=Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\b");
    public record Stages(String vertex,String fragment){}
    public static Stages adapt(String source,String fragment){
        int split=source.indexOf("#extension GL_ARB_explicit_attrib_location : require");
        int bridge=source.indexOf("// Six RGBA32F texels per current PMX vertex");
        if(split<0||bridge<split||!source.substring(0,split).contains("uniform samplerBuffer uPalettes;"))
            throw new IllegalArgumentException("Unreviewed GF2 native vertex ABI");
        String original=source.substring(split,bridge),tail=source.substring(bridge);
        String jitter="gl_Position.xy+=gf2ClipJitter*gl_Position.w;";
        if(tail.indexOf(jitter)<0||tail.indexOf(jitter)!=tail.lastIndexOf(jitter))throw new IllegalArgumentException("Unreviewed original clip transport");
        tail="uniform float gf2ClipScale=1.0;\n"+AimohGf2Screen.glsl()+tail.replace(jitter,"gl_Position=aimohEclipseClip(gl_Position,gf2ClipJitter,gf2ClipScale);");
        tail=Pattern.compile("texelFetch\\((uPalettes|uDraws),([^(),]+)\\)").matcher(tail).replaceAll("$1[$2]");
        if(tail.contains("texelFetch(uPalettes,")||tail.contains("texelFetch(uDraws,"))
            throw new IllegalArgumentException("Unmapped GF2 native palette fetch");
        int normalLocation=0;Matcher output=OUTPUT_LOCATION.matcher(original);
        while(output.find())normalLocation=Math.max(normalLocation,Integer.parseInt(output.group(1))+1);
        String normal="layout(location = "+normalLocation+") ";
        tail=tail.replace("out vec3 gf2NativeWorldNormal;",normal+"out vec3 gf2NativeWorldNormal;");
        fragment=fragment.replace("in vec3 gf2NativeWorldNormal;",normal+"in vec3 gf2NativeWorldNormal;");
        // Host varyings are only scratch values inside fetchModel, not this program's stage interface.
        String host=HOST_OUTPUT.matcher(NATIVE).replaceAll("$1");
        return new Stages(host+original+tail,fragment);
    }
}
