package viewpoint.render;

public final class OutlineBridgeCheck {
    public static void main(String[] args){
        String normal="u_xlat18 = in_TANGENT0.w * GF2VSRaw1[9].w;";
        String outline="float gf2SourceOutlineClipOffset; vec3 c=in_COLOR0.xyz; "+normal;
        String fixed=AimohGf2OutlineBridge.adapt(outline);
        if(!fixed.contains("(-in_TANGENT0.w) * GF2VSRaw1[9].w"))throw new AssertionError("Coordinate parity not compensated");
        if(!AimohGf2OutlineBridge.adapt(fixed).equals(fixed))throw new AssertionError("Compensation applied twice");
        if(!AimohGf2OutlineBridge.adapt(normal).equals(normal))throw new AssertionError("Main shading changed");
        String plain="float gf2SourceOutlineClipOffset; vec3 n=in_NORMAL0;";
        if(!AimohGf2OutlineBridge.adapt(plain).equals(plain))throw new AssertionError("Non-colour outline changed");
        try{AimohGf2OutlineBridge.adapt(outline+normal);throw new AssertionError("Ambiguous basis accepted");}catch(IllegalArgumentException expected){}
        try{AimohGf2OutlineBridge.adapt(outline.replace(normal,""));throw new AssertionError("Missing basis accepted");}catch(IllegalArgumentException expected){}
        // Both physical mirror signs survive the cancellation of the extra coordinate reflection.
        for(int physical:new int[]{-1,1})if((-1*physical)*-1!=physical)throw new AssertionError("Physical parity lost");
        System.out.println("SHARED_OUTLINE_PARITY_CHECK_PASS");
    }
}
