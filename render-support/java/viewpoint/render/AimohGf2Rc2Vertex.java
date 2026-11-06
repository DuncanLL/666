package viewpoint.render;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.regex.Pattern;
/** Host-version ABI only; source character formulas and material values are untouched. */
public final class AimohGf2Rc2Vertex {
    private static final String NATIVE=AimohGf2NativeShaders.modelVertex();
    public static String adapt(String source){
        int split=source.indexOf("#extension GL_ARB_explicit_attrib_location : require");
        int bridge=source.indexOf("// Six RGBA32F texels per current PMX vertex");
        if(split<0||bridge<split||!source.substring(0,split).contains("uniform samplerBuffer uPalettes;"))
            throw new IllegalArgumentException("Unreviewed GF2 native vertex ABI");
        String original=source.substring(split,bridge),tail=source.substring(bridge);
        tail=Pattern.compile("texelFetch\\((uPalettes|uDraws),([^(),]+)\\)").matcher(tail).replaceAll("$1[$2]");
        if(tail.contains("texelFetch(uPalettes,")||tail.contains("texelFetch(uDraws,"))
            throw new IllegalArgumentException("Unmapped GF2 native palette fetch");
        return NATIVE+original+tail;
    }
}
