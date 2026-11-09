package viewpoint.render;

import java.util.regex.*;

/** The PMX-to-source bridge adds one fixed Z reflection to the source object frame. */
public final class AimohGf2OutlineBridge {
    private static final String MARKER="/* GF2 outline coordinate parity */";
    private static final Pattern OBJECT_BITANGENT=Pattern.compile("\\bin_TANGENT0\\.w\\s*\\*\\s*GF2VSRaw\\d+\\[\\d+\\]\\.w");
    public static String adapt(String source){
        if(source.contains(MARKER)||!source.contains("gf2SourceOutlineClipOffset")||!source.contains("in_COLOR0.xyz"))return source;
        Matcher match=OBJECT_BITANGENT.matcher(source);
        if(!match.find())throw new IllegalArgumentException("Unreviewed source outline object-space bitangent");
        int begin=match.start(),end=match.end();String term=match.group();
        if(match.find())throw new IllegalArgumentException("Ambiguous source outline object-space bitangent");
        // Cancel coordinate reflection only; retain the source term, material data and physical mirror sign.
        return source.substring(0,begin)+MARKER+term.replace("in_TANGENT0.w","(-in_TANGENT0.w)")+source.substring(end);
    }
    private AimohGf2OutlineBridge(){}
}
