"""Build the Somber variant from the same Native character payload and the pinned VP host."""
import io
import zipfile
from pathlib import Path

from build_render_support import write, check_glsl_names
from common import ROOT

OVERLAY = ROOT / 'render-support/somber'
PAYLOAD = 'META-INF/gfl2/rendering/somber.zip'


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError(f'Somber input changed at {old!r}')
    return source.replace(old, new)


def properties(native):
    replacements = {
        'name': 'Somber GFL 2',
        'description': 'Official Somber scene lighting with shared GFL 2 character materials.',
        'base': 'somber',
        'option.characterDisplayStrength.default': '1',
        'option.characterDisplayStrength.tooltip': '控制角色使用原游颜色查找表的强度。',
    }
    lines = []
    for line in native.decode().splitlines():
        key = line.partition('=')[0]
        if key in replacements:
            line = f'{key}={replacements.pop(key)}'
        lines.append(line)
    if replacements:
        raise ValueError(f'Missing Native settings: {list(replacements)}')
    return ('\n'.join(lines) + '\n').encode()


def character_post(host):
    with zipfile.ZipFile(host) as archive:
        source = archive.read('viewpoint/shaderpacks/somber/shaders/post.frag').decode()
    source = replace_once(source, 'uniform float uInkScale;',
                          'uniform float uInkScale;\nuniform bool gf2HasCoverage;\nuniform sampler2D gf2OriginalNormals;')
    source = replace_once(source, 'void main() {', '#include "gf2/somber_actor_display.glsl"\nvoid main() {')
    source = replace_once(source, 'float ink = 0.0;', '''float ink = 0.0;
  bool gf2Actor = false;
  if (gf2HasCoverage) {
    ivec2 dims = textureSize(gf2OriginalNormals, 0);
    ivec2 pixel = clamp(ivec2(vUv * vec2(dims)), ivec2(0), dims-1);
    vec2 tags = texelFetch(gf2OriginalNormals, pixel, 0).zw;
    gf2Actor = any(greaterThan(abs(tags), vec2(0.0001))) &&
        abs(texelFetch(uAlbedo, pixel, 0).a - 31.0/255.0) < 0.5/255.0;
  }
  float gf2Coverage = gf2Actor ? 1.0 - layered : 0.0;''')
    source = replace_once(source, 'if (!share) ink = inkAt(vUv, OPTION_INK_WIDTH) * OPTION_INK_STRENGTH;',
                          'if (!share && gf2Coverage < 1.0) ink = inkAt(vUv, OPTION_INK_WIDTH) * OPTION_INK_STRENGTH * (1.0-gf2Coverage);')
    grade = 'color = grade(color, depth >= 1.0 && (uFarOn < 0.5 || texture(uFarDepth, vUv).r >= 1.0), dark, middle);'
    return replace_once(source, grade, '''if (gf2Coverage >= 1.0) {
    color = gf2ActorGrade(color, dark, middle);
  } else {
    vec3 hdr = color;
    ''' + grade + '''
    if (gf2Coverage > 0.0) color = mix(color, gf2ActorGrade(hdr, dark, middle), gf2Coverage);
  }''').encode()


def build_somber(native_payload, host):
    overlay = {p.relative_to(OVERLAY).as_posix(): p.read_bytes()
               for p in sorted(OVERLAY.rglob('*')) if p.is_file()}
    for name, data in overlay.items():
        check_glsl_names(name, data)
    overlay['shaders/post.frag'] = character_post(host)
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(native_payload)) as native, zipfile.ZipFile(output, 'w') as target:
        overlay['pack.properties'] = properties(native.read('pack.properties'))
        for name in native.namelist():
            if name not in overlay and not name.endswith('/'):
                write(target, name, native.read(name))
        for name, data in overlay.items():
            write(target, name, data)
    return output.getvalue()
