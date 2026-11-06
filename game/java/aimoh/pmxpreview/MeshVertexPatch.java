package aimoh.pmxpreview;
import me.zed_0xff.zombie_buddy.Patch;

/** Compile-time support for previous positions on pre-deformed meshes. Does not alter lighting. */
@Patch(className="viewpoint.platform.Shaders",methodName="read")
public final class MeshVertexPatch {
    public static String adapt(String source) {
        String skin="bool skinned = dot(aWeights, vec4(1.0)) > 0.0;",previous="vec4 q = vec4(aPos, 1.0);";
        if(source==null||source.contains("VP_PMX_DEFORM1")||!source.contains(skin)||!source.contains(previous))return source;
        return source.replace(skin,"// VP_PMX_DEFORM1: aWeights.xyz carries the previous rendered position.\n  bool pmxDeformed = aWeights.w < -1.0e19;\n  bool skinned = !pmxDeformed && dot(aWeights, vec4(1.0)) > 0.0;")
            .replace(previous,"vec4 q = vec4(pmxDeformed ? aWeights.xyz : aPos, 1.0);");
    }
    @Patch.OnExit public static void exit(@Patch.Return(readOnly=false) String source){source=adapt(source);}
}
