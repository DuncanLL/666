package viewpoint.render;

/** Eclipse retains its own viewport scale and jitter transport. */
public final class AimohGf2Rc2Vertex {
    private static final String EYE_PARALLAX="vs_TEXCOORD5.x = u_xlat0.x * GF2VSRaw1[9].w;";
    public record Stages(String vertex,String fragment){}
    public static Stages adapt(String source,String fragment){
        String vertex=AimohGf2Vertex.adapt(eyeParallax(source));
        int bridge=vertex.indexOf("// Six RGBA32F texels per current PMX vertex");
        String tail=vertex.substring(bridge);
        String jitter="gl_Position.xy+=gf2ClipJitter*gl_Position.w;";
        if(tail.indexOf(jitter)<0||tail.indexOf(jitter)!=tail.lastIndexOf(jitter))
            throw new IllegalArgumentException("Unreviewed original clip transport");
        String eclipse=vertex.substring(0,bridge)+"uniform float gf2ClipScale=1.0;\n"+AimohGf2Screen.glsl()
            +tail.replace(jitter,"gl_Position=aimohEclipseClip(gl_Position,gf2ClipJitter,gf2ClipScale);");
        var stages=AimohGf2Vertex.bindStages(eclipse,fragment);
        return new Stages(stages.vertex(),stages.fragment());
    }

    private static String eyeParallax(String source){
        int term=source.indexOf(EYE_PARALLAX);
        if(term<0)return source;
        if(term!=source.lastIndexOf(EYE_PARALLAX)
            ||!source.contains("u_xlat0.x = u_xlat0.x * in_COLOR0.y;")
            ||!source.contains("u_xlat0.x = u_xlat0.x * in_TANGENT0.w;"))
            throw new IllegalArgumentException("Unreviewed original eye parallax");
        // The bridge's fixed Z reflection is absent from the original eye material's handedness.
        return source.replace(EYE_PARALLAX,"vs_TEXCOORD5.x = u_xlat0.x * (-GF2VSRaw1[9].w);");
    }
}
