// The renderer prepares GF2 opaque colour with the inverse of this Filmic response.
vec3 gf2ActorGrade(vec3 hdr, float dark, float middle) {
  float luma;
  vec3 light = max(hdr * uExposure * OPTION_EXPOSURE * darkLift(dark, middle), vec3(0.0));
  vec3 display = toneFilmic(purkinje(light), luma);
  display = mix(vec3(luma), display, OPTION_SATURATION);
  float peak = max(display.r, max(display.g, display.b));
  float chroma = peak > 1e-4 ? (peak - min(display.r, min(display.g, display.b))) / peak : 0.0;
  return darkSeen(max(mix(vec3(luma), display, 1.0 + OPTION_VIBRANCE * (1.0 - chroma)), vec3(0.0)), dark);
}

