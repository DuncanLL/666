"""Basic Viewpoint lighting adapters for the public source view."""


NATIVE = {
    'native_indirect.glsl': ('void gf2NativeInitIndirect', '''
void gf2NativeInitIndirect(vec3 position) {
    vec3 normal = texelFetch(gf2NativeNormals, ivec2(gl_FragCoord.xy), 0).xyz;
    gf2NativeBouncedIrradiance = gf2NativeIndirectOn != 0 && dot(normal, normal) > 0.01
        ? max(bounceAt(position, normalize(normal)), vec3(0.0)) : vec3(0.0);
}
vec4 gf2NativeSH(int channel, vec4 coefficient) {
    return coefficient + vec4(0.0, 0.0, 0.0, gf2NativeBouncedIrradiance[channel] * 0.5);
}
'''),
    'native_main.glsl': ('// Incident visibility', '''
float gf2NativeMainVisibility(vec3 position, vec3 normal) {
    float shade = 1.0;
#if PIPELINE_SHADOWS || PIPELINE_FAR_SHADOWS
    if (uShadowsOff < 0.5) shade = sunVisibility(position, normal, max(dot(normal, uSunDir), 0.0));
#endif
    return mix(0.25, 1.0, shade * cloudShadow(position));
}
'''),
    'native_cube.comp': ('// Current-frame', '''
void main() {
    ivec3 p = ivec3(gl_GlobalInvocationID);
    ivec2 size = imageSize(sourceCube);
    if (any(greaterThanEqual(p.xy, size)) || p.z >= 6) return;
    vec2 uv = (vec2(p.xy) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec3 ray = normalize(cubeDirection(p.z, uv)) * vec3(-1, 1, -1);
    vec3 colour = reflectedSky(gf2ProbePosition, ray, clamp(gf2ProbeSkyShare, 0.0, 1.0));
    if (gf2ProbeRoomOn != 0) {
        vec4 room = textureLod(gf2ProbeRoom, ray, 0.0);
        colour = mix(colour, room.rgb, room.a);
    }
    if (gf2ProbeSceneOn != 0) {
        vec4 clip = gf2ProbeViewProjection * vec4(gf2ProbePosition + ray * 2.0, 1.0);
        if (clip.w > 0.0) {
            vec2 screen = clip.xy / clip.w * 0.5 + 0.5;
            if (all(greaterThan(screen, vec2(0.0))) && all(lessThan(screen, vec2(1.0)))) {
                vec3 reflected = textureLod(gf2ProbeScene, screen, 0.0).rgb;
                float opacity = textureLod(gf2ProbeAlbedo, screen, 0.0).a;
                colour = mix(colour, reflected, 0.2 * (1.0 - opacity));
            }
        }
    }
    imageStore(sourceCube, p, vec4(max(colour, vec3(0.0)), 1.0));
}
'''),
    'native_light.comp': ('void main()', '''
void main() {
    vec3 sky = max(texelFetch(nativeSkyTexture, ivec2(0), 0).rgb, vec3(0.0));
    vec3 ambient = mix(max(nativeEngineLight.rgb, vec3(0.0)), sky, clamp(nativeEngineLight.a, 0.0, 1.0));
    for (int channel = 0; channel < 7; ++channel) sourceSH[channel] = vec4(0.0);
    sourceSH[0].w = ambient.r;
    sourceSH[1].w = ambient.g;
    sourceSH[2].w = ambient.b;
    sourceMain = vec4(max(nativeRelativeSky, vec3(0.0)) * max(nativePrimaryStrength, 0.0), 1.0);
}
'''),
    'native_local.glsl': ('vec4 gf2NativeLocalColor', '''
vec4 gf2NativeLocalColor(int slot, vec4 colour, vec3 position, vec3 normal) {
    if (slot >= gf2NativeLocalCount) return colour;
    int kind = gf2NativeLocalKind[slot];
    int i = gf2NativeLocalIndex[slot];
    vec3 source;
    float reach;
    if (kind == 0) {
        source = uLightPos[i].xyz;
        reach = uLightPos[i].w;
    } else if (kind == 1) {
        source = uTorchPos[i].xyz;
        reach = uTorchPos[i].w;
    } else {
        Spot spot = spotAt(i);
        source = spot.pos;
        reach = spot.reach;
    }
    vec3 offset = source - position;
    float distance = length(offset);
    float falloff = max(0.0, 1.0 - distance / max(reach, 0.01));
    float facing = mix(0.5, 1.0, max(dot(normalize(normal), offset / max(distance, 0.01)), 0.0));
    return vec4(colour.rgb * falloff * falloff * facing * dot(offset, offset), colour.a);
}
'''),
    'native_motion.comp': ('vec3 originalDisplayInput', '''
void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(pixel, imageSize(nativeVelocity)))) return;
    if (initializeNormals != 0) {
        vec3 normal = imageLoad(nativeNormals, pixel).xyz;
        imageStore(originalNormals, pixel, vec4(normal.xy * 0.5 + 0.5, 0.0, 0.0));
        return;
    }
    vec4 motion = texelFetch(sourceNativeMotion, pixel, 0);
    if (motion.w > 0.5 && motion.z > 0.0) imageStore(nativeVelocity, pixel, motion);
    vec4 normal = imageLoad(originalNormals, pixel);
    if (normal.z > 0.0) {
        vec2 xy = normal.xy * 2.0 - 1.0;
        imageStore(nativeNormals, pixel, vec4(normalize(vec3(xy, 1.0)), 1.0));
    }
}
'''),
    'native_prefilter.comp': ('const uint SAMPLE_COUNT', '''
void main() {
    ivec3 pixel = ivec3(gl_GlobalInvocationID);
    ivec2 size = imageSize(filteredCube);
    if (any(greaterThanEqual(pixel.xy, size)) || pixel.z >= 6) return;
    vec2 uv = (vec2(pixel.xy) + 0.5) / vec2(size) * 2.0 - 1.0;
    float width = sourcePerceptualRoughness * 0.15;
    vec3 filtered = textureLod(nativeRawCube, cubeDirection(pixel.z, uv), 0.0).rgb * 0.5;
    filtered += textureLod(nativeRawCube, cubeDirection(pixel.z, uv + vec2(width)), 0.0).rgb * 0.25;
    filtered += textureLod(nativeRawCube, cubeDirection(pixel.z, uv - vec2(width)), 0.0).rgb * 0.25;
    imageStore(filteredCube, pixel, vec4(filtered, 1.0));
}
'''),
}


def native_shader(name, source):
    if name == 'cube_faces.glsl':
        return source
    anchor, replacement = NATIVE[name]
    prefix, found, _ = source.decode().partition(anchor)
    if not found:
        raise ValueError(f'Native public shader anchor changed: {name}')
    return (prefix.rstrip() + '\n' + replacement).encode()


def public_manifest(formal):
    manifest = dict(formal)
    fill = manifest.get('original_camera_fill')
    if fill is not None:
        manifest['original_camera_fill'] = {
            'camera_local_direction': [0, 0.6, -0.8],
            'color': [0.5, 0.5, 0.5, 1.0],
            'unity_camera_to_world_z_parity': fill['unity_camera_to_world_z_parity'],
        }
    return manifest


ECLIPSE_SKY = '''#version 430 compatibility
uniform sampler2D colortex4;
layout(location=0) out vec4 result;

vec3 sampleSky(vec2 uv) {
    vec2 tile = vec2(276.0, 2.0) + clamp(uv, 0.0, 1.0) * 252.0;
    return max(textureLod(colortex4, tile / vec2(textureSize(colortex4, 0)), 0.0).rgb, vec3(0.0)) * 0.001;
}
'''

ECLIPSE_FRAGMENTS = {
    'sky_base.fsh': '''
/* RENDERTARGETS:20 */
void main() {
    vec3 average = (sampleSky(vec2(0.25, 0.35)) + sampleSky(vec2(0.75, 0.65))) * 0.5;
    result = int(gl_FragCoord.x) == 0 ? vec4(average, 1.0) : vec4(0.0);
}
''',
    'sky_filter.fsh': '''
/* RENDERTARGETS:21 */
void main() {
    vec2 uv = mod(gl_FragCoord.xy, 64.0) / 64.0;
    ivec2 tile = ivec2(gl_FragCoord.xy) / 64;
    float amount = float(tile.x + tile.y * 3) / 5.0;
    vec3 average = sampleSky(vec2(0.5));
    result = vec4(mix(sampleSky(uv), average, amount * 0.5), 1.0);
}
''',
}

ECLIPSE_KERNELS = {
    'CUBE': '''
void main() {
    ivec3 p = ivec3(gl_GlobalInvocationID);
    ivec2 size = imageSize(destination);
    if (any(greaterThanEqual(p.xy, size)) || p.z >= 6) return;
    vec2 uv = (vec2(p.xy) + 0.5) / vec2(size);
    int tile = min(level, 5);
    uv.x = fract((uv.x + float(p.z)) / 6.0);
    vec2 atlas = (vec2(tile % 3, tile / 3) + uv) * 64.0;
    imageStore(destination, p, vec4(textureLod(sourceSky, atlas / vec2(textureSize(sourceSky, 0)), 0.0).rgb, 1.0));
}
''',
    'ENVIRONMENT_SH': '''
void main() {
    vec3 c[1];
    c[0] = texelFetch(sourceSkySH, ivec2(0), 0).rgb * sourceEnvironmentScale;
    vec3 ambient = c[0];
    sh[0] = vec4(0.0, 0.0, 0.0, ambient.r);
    sh[1] = vec4(0.0, 0.0, 0.0, ambient.g);
    sh[2] = vec4(0.0, 0.0, 0.0, ambient.b);
    for (int i = 3; i < 7; ++i) sh[i] = vec4(0.0);
    mainLight=vec4(0,0,0,1);
    if(copyNativeMain)mainLight.rgb=vec3(nativeLightFields[nativeMainFloatOffset],nativeLightFields[nativeMainFloatOffset+1],nativeLightFields[nativeMainFloatOffset+2])*nativeMainScale;
}
''',
    'MOTION_DECODE': '''
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, imageSize(nativeMotion)))) return;
    vec2 velocity = texelFetch(sourceMotion, p, 0).xy / max(sourceViewport, vec2(1.0));
    if (all(lessThan(abs(velocity), vec2(1.0)))) imageStore(nativeMotion, p, vec4(velocity.x, -velocity.y, 0.0, 0.0));
}
''',
}


def eclipse_shader(name, source, kernels):
    basename = name.rsplit('/', 1)[-1]
    if basename in ECLIPSE_FRAGMENTS:
        return (ECLIPSE_SKY + ECLIPSE_FRAGMENTS[basename]).encode()
    if basename.endswith('.vsh'):
        return source
    key, = (key for key, row in kernels.items() if name.endswith(row['path']))
    return source.split(b'void main()', 1)[0] + ECLIPSE_KERNELS[key].encode()


def eclipse_probe(prefix):
    return prefix + b'''
uniform vec3 uSceneOrigin;
layout(local_size_x=1) in;
layout(std430,binding=0) readonly buffer BaseUnitySH { vec4 sourceSH[7]; vec4 sourceMain; };
layout(std430,binding=1) writeonly buffer ActorUnitySH { vec4 actorSH[7]; vec4 reflectionHDR; vec4 cameraLightColor; };
uniform vec3 actorAnchor;
uniform bool nativeSkyAvailable;
uniform vec3 nativeMinimumLight;
uniform samplerBuffer gf2NativeExposure;
uniform int gf2NativeExposureOffset;

void main() {
    float visible = nativeSkyAvailable ? 0.8 : 1.0;
    float ambient = max(nativeMinimumLight.x, 0.0) * 0.02;
    vec3 diffuseReference = vec3(0.25);
    vec3 budgetedLevel = max(uSkyLevel, vec3(0.0));
    vec3 mean = vec3(sourceSH[0].w, sourceSH[1].w, sourceSH[2].w) * visible;
    vec3 missing = vec3(ambient);
    for (int i = 0; i < 7; ++i) {
        vec4 light = sourceSH[i] * visible;
        if (i < 3) light.w += ambient;
        actorSH[i] = light;
    }
    actorSH[6].w=1;
    reflectionHDR = vec4(visible, 1.0, 0.0, 0.0);
    cameraLightColor = vec4(vec3(0.45 * visible), 1.0);
}
'''


ECLIPSE_COMPOSITE = b'''
 ivec2 actorPixel = ivec2(closestToCamera.xy / texelSize);
 if (all(greaterThanEqual(actorPixel, ivec2(0))) &&
     all(lessThan(actorPixel, min(textureSize(colortex17, 0), textureSize(vpDanMotionTex, 0)))) &&
     texelFetch(colortex17, actorPixel, 0).r > 0.5) {
     vec2 motion = texelFetch(vpDanMotionTex, actorPixel, 0).rg;
     if (all(lessThan(abs(motion), vec2(1000.0)))) velocity = -motion;
 }
'''
