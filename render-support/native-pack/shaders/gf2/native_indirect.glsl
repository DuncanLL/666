#version 430
#include "constants.glsl"
#include "lib/bounce.glsl"
uniform sampler2D gf2NativeNormals;
uniform int gf2NativeIndirectOn;
vec3 gf2NativeBouncedIrradiance;

void gf2NativeInitIndirect(vec3 pos){
    gf2NativeBouncedIrradiance=vec3(0.0);
    if(gf2NativeIndirectOn==0)return;
    vec3 normal=texelFetch(gf2NativeNormals,ivec2(gl_FragCoord.xy),0).xyz;
    if(dot(normal,normal)<1e-6)return;
    normal=normalize(normal);
    gf2NativeBouncedIrradiance=bounceAt(pos,normal)+flashBounceAt(pos,normal);
}

vec4 gf2NativeSH(int channel,vec4 coefficient){
    // Original SH evaluation has n.w=1. Add actual Native diffuse bounce to its constant term.
    return vec4(coefficient.xyz,coefficient.w+gf2NativeBouncedIrradiance[channel]);
}
