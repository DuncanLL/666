#version 430
#include "constants.glsl"
#include "lib/sun.glsl"

// Incident visibility only; original material N.L, ramp and BRDF remain downstream.
float gf2NativeMainVisibility(vec3 pos,vec3 normal){
    float visibility=1.0;
#if PIPELINE_SHADOWS || PIPELINE_FAR_SHADOWS
    if(uShadowsOff<=.5)visibility=sunVisibility(pos,normal,clamp(dot(normal,uSunDir),0.0,1.0));
#endif
    return visibility*cloudShadow(pos);
}
