#ifndef GF2_NATIVE_REFLECTION_SKY
#define GF2_NATIVE_REFLECTION_SKY
// Somber's reflected.glsl is a screen-space water/material service for fragment stages.
vec3 reflectedSky(vec3 pos, vec3 direction, float visibility) {
    return skyColor(direction, vec3(0.0), vec3(0.0), uSunDir, uSunColor, uSunStrength)
        * (SKY_BRIGHTNESS * visibility);
}
#endif
