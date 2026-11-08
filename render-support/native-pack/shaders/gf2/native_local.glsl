#version 430
// Native emitter/visibility services; source materials keep their own BRDF and ramp.
#include "constants.glsl"
uniform int uLightCount;
uniform vec4 uLightPos[48];
uniform vec3 uLightColor[48];
#include "lib/lamp_shadow.glsl"
#include "lib/torches.glsl"
#include "lib/spots.glsl"
#ifndef GF2_NATIVE_LAMP_PEAK
#error Native lamp service must use the actual drawn pack attenuation constant
#endif
uniform int gf2NativeLocalCount;
uniform int gf2NativeLocalKind[7];
uniform int gf2NativeLocalIndex[7];

vec4 gf2NativeLocalColor(int slot,vec4 colour,vec3 pos,vec3 normal){
    if(slot>=gf2NativeLocalCount){
        // Reuse the camera-relative fill slot; zero preserves its original colour exactly.
        float fill=float(OPTION_CHARACTER_SOFT_FILL);
        if(fill<=0.0)return colour;
        float level=max(colour.r,max(colour.g,colour.b));
        return vec4(colour.rgb+level*fill*vec3(0.90,0.97,1.0),colour.a);
    }
    int kind=gf2NativeLocalKind[slot],i=gf2NativeLocalIndex[slot];
    vec3 n=normalize(normal),d;float dist,energy;
    if(kind==0){
        d=pos-uLightPos[i].xyz;dist=length(d);
        if(dist>=uLightPos[i].w)return vec4(0,0,0,colour.a);
        // Actual default lib/shade.glsl lamps(), before its material-facing term.
        float att=1.0-dist/uLightPos[i].w;
        energy=att*att*(1.0+GF2_NATIVE_LAMP_PEAK*pow(att,6.0))*lampVisibility(i,pos,n);
    }else if(kind==1){
        d=pos-uTorchPos[i].xyz;dist=length(d);
        if(dist>=uTorchPos[i].w)return vec4(0,0,0,colour.a);
        float edge=uTorchDir[i].w;
        float beam=edge<=-1.0?1.0:smoothstep(edge,mix(edge,1.0,0.3),dot(d/max(dist,1e-4),uTorchDir[i].xyz));
        energy=beam*(1.0-dist/uTorchPos[i].w)*torchShadow(i,pos,n);
    }else{
        Spot s=spotAt(i);d=pos-s.pos;dist=length(d);
        if(dist>=s.reach)return vec4(0,0,0,colour.a);
        energy=spotBeam(s,d/max(dist,1e-4))*spotFalloff(s,dist)*spotShadow(s,pos,n,dist,true)*spotNose(s,d);
    }
    // Neutral source attenuation leaves 1/d². Cancel only that incident-light factor.
    return vec4(colour.rgb*(max(dot(d,d),1.17549435e-38)*energy),colour.a);
}
