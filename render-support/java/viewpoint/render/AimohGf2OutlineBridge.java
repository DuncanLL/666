package viewpoint.render;

import java.util.regex.*;

/** The PMX-to-source bridge adds one fixed Z reflection to the source object frame. */
public final class AimohGf2OutlineBridge {
    private static final String MARKER="/* GF2 outline coordinate parity */";
    private static final String DEPTH_OFFSET="float gf2SourceOutlineClipOffset;";
    private static final String EXTRUSION="gl_Position.xy = u_xlat0.xy * in_COLOR0.ww + u_xlat1.xy;";
    private static final Pattern OBJECT_BITANGENT=Pattern.compile("\\bin_TANGENT0\\.w\\s*\\*\\s*GF2VSRaw\\d+\\[\\d+\\]\\.w");
    public static String adapt(String source){
        if(source.contains(MARKER)||!source.contains("gf2SourceOutlineClipOffset")||!source.contains("in_COLOR0.xyz"))return source;
        Matcher match=OBJECT_BITANGENT.matcher(source);
        if(!match.find())throw new IllegalArgumentException("Unreviewed source outline object-space bitangent");
        int begin=match.start(),end=match.end();String term=match.group();
        if(match.find())throw new IllegalArgumentException("Ambiguous source outline object-space bitangent");
        if(!unique(source,DEPTH_OFFSET)||!unique(source,EXTRUSION))
            throw new IllegalArgumentException("Unreviewed source outline extrusion");
        // Keep the authored depth bias and winding. Scale only the screen-space displacement.
        String fixed=source.substring(0,begin)+MARKER+term.replace("in_TANGENT0.w","(-in_TANGENT0.w)")+source.substring(end);
        return fixed.replace(DEPTH_OFFSET,"uniform float gf2CharacterOutlineWidth = 1.0;\n"+DEPTH_OFFSET)
            .replace(EXTRUSION,"gl_Position.xy = u_xlat0.xy * in_COLOR0.ww * gf2CharacterOutlineWidth + u_xlat1.xy;");
    }
    private static boolean unique(String source,String value){int first=source.indexOf(value);return first>=0&&first==source.lastIndexOf(value);}
    private AimohGf2OutlineBridge(){}
}
