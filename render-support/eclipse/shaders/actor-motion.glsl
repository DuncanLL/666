 ivec2 at=ivec2(closestToCamera.xy/texelSize);
 if(all(greaterThanEqual(at,ivec2(0))) &&
    all(lessThan(at,textureSize(colortex17,0))) &&
    texelFetch(colortex17,at,0).r>.5){
     // The character target is 1x1 when no source actor is drawn.
     if(all(lessThan(at,textureSize(vpDanMotionTex,0)))){
         vec2 actorMotion=texelFetch(vpDanMotionTex,at,0).rg;
         if(all(lessThan(abs(actorMotion),vec2(65000))))
             velocity=-actorMotion;
     }
 }
