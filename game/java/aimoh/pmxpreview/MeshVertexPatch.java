package aimoh.pmxpreview;
import aimoh.motion.PmxDrawMesh;
import me.zed_0xff.zombie_buddy.Patch;

/** Compile-time support for previous positions on pre-deformed meshes. Does not alter lighting. */
@Patch(className="viewpoint.platform.Shaders",methodName="read")
public final class MeshVertexPatch {
    public static String adapt(String source) {
        String skin="bool skinned = dot(aWeights, vec4(1.0)) > 0.0;",previous="vec4 q = vec4(aPos, 1.0);";
        if(source==null||source.contains("VP_PMX_DEFORM")||!source.contains(skin))return source;
        String declaration="// VP_PMX_DEFORM2: previous positions belong only to explicitly tagged draws.\n  bool pmxDeformed = (int(indices.w) & "+PmxDrawMesh.DEFORMED+") != 0;\n  bool skinned = !pmxDeformed && dot(aWeights, vec4(1.0)) > 0.0;";
        if(source.contains(previous))return source.replace(skin,declaration)
            .replace(previous,"vec4 q = vec4(pmxDeformed ? aWeights.xyz : aPos, 1.0);");
        String signature="vec4 posed(int palette, bool skinned, out vec3 normal)",
            current="posed(int(uShadowPalette == 1 ? indices.y : indices.x), skinned, n)",
            before="posed(int(indices.z), skinned, before)";
        if(!source.contains(signature)||!source.contains(current)||!source.contains(before))return source;
        return source.replace(skin,declaration)
            .replace(signature,"vec4 posed(int palette, bool skinned, vec3 position, out vec3 normal)")
            .replace("vec4 p = vec4(aPos, 1.0);","vec4 p = vec4(position, 1.0);")
            .replace(current,"posed(int(uShadowPalette == 1 ? indices.y : indices.x), skinned, aPos, n)")
            .replace(before,"posed(int(indices.z), skinned, pmxDeformed ? aWeights.xyz : aPos, before)");
    }
    @Patch.OnExit public static void exit(@Patch.Return(readOnly=false) String source){source=adapt(source);}
}
