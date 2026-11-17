"""Build the missing UI and caption glyphs using locally configured Noto fonts."""
from collections import Counter
import json
from pathlib import Path
import re
import shutil
import sys
from tempfile import TemporaryDirectory

from common import BUILD, ROOT, settings


FONT_NAMES = {'CN': 'Chinese', 'EN': '', 'JP': 'Japanese', 'KO': 'Korean'}
CAPTION_FIELDS = {'CN': 'chinese', 'JP': 'japanese'}
MEDIA = ROOT / 'game/mod/42/media'
PACKS = ROOT / 'game/packs'


def catalog_strings(value):
    if isinstance(value, dict):
        for key, item in value.items():
            yield key
            yield from catalog_strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from catalog_strings(item)
    elif isinstance(value, str):
        yield value


def required_characters(language):
    texts = []
    if language in CAPTION_FIELDS:
        field = re.compile(r'\b' + CAPTION_FIELDS[language] + r'\s*=\s*"((?:\\\d{3})+)"')
        for script in PACKS.glob('*/42/media/lua/**/*.lua'):
            if 'Voice' not in script.as_posix():
                continue
            for encoded in field.findall(script.read_text('utf-8-sig')):
                texts.append(bytes(map(int, re.findall(r'\\(\d{3})', encoded))).decode('utf-8'))
        assert texts, language
    translation_paths = list((MEDIA / 'lua/shared/Translate' / language).glob('*.json'))
    translation_paths.extend(PACKS.glob(f'*/42/media/lua/shared/Translate/{language}/*.json'))
    for path in translation_paths:
        texts.extend(json.loads(path.read_text('utf-8-sig')).values())
    if language == 'CN':
        catalog = json.loads((MEDIA / 'ui/GFL2ReadyRoom/catalog.json').read_text('utf-8'))
        texts.extend(text for text in catalog_strings(catalog) if re.search(r'[\u3400-\u9fff]', text))
    return {char for text in texts for char in text if not char.isspace()}


def font_details(path):
    lines = path.read_text('utf-8-sig').splitlines()
    common = dict(re.findall(r'(\w+)=(\d+)', lines[1]))
    chars = {}
    for line in lines:
        if line.startswith('char '):
            record = dict(re.findall(r'(\w+)=(-?\d+)', line))
            chars[chr(int(record['id']))] = int(record['xadvance'])
    advances = Counter(width for char, width in chars.items() if '\u4e00' <= char <= '\u9fff')
    baseline = int(common['base'])
    em = advances.most_common(1)[0][0] if advances else baseline
    return (int(common['lineHeight']), baseline, em), chars.keys()


def render_font(jpype, font_file, fallback_file, glyphs, metrics, output):
    J = jpype.JClass
    line_height, baseline, em = metrics
    image_type = J('java.awt.image.BufferedImage')
    hints = J('java.awt.RenderingHints')
    font_type = J('java.awt.Font')
    font = font_type.createFont(font_type.TRUETYPE_FONT, J('java.io.File')(str(font_file))).deriveFont(jpype.JFloat(em))
    fallback = None
    cell_width, cell_height = em * 2 + 4, line_height + 4
    atlas_size = 512 if len(glyphs) > 1 else 64
    atlas_size = max(atlas_size, 128 if cell_width > 64 else 64)
    columns = atlas_size // cell_width
    per_page = columns * (atlas_size // cell_height)
    pages, definitions = [], []
    output.mkdir(parents=True, exist_ok=True)
    symbols = sorted(glyphs)
    for start in range(0, len(symbols), per_page):
        page = len(pages)
        pages.append(f'glyphs-{page}.png')
        image = image_type(atlas_size, atlas_size, image_type.TYPE_INT_ARGB)
        graphics = image.createGraphics()
        graphics.setColor(J('java.awt.Color').WHITE)
        graphics.setRenderingHint(hints.KEY_TEXT_ANTIALIASING, hints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setFont(font)
        context = graphics.getFontRenderContext()
        for index, char in enumerate(symbols[start:start + per_page]):
            selected = font
            if not selected.canDisplay(ord(char)) and fallback_file != font_file:
                if fallback is None:
                    fallback = font_type.createFont(font_type.TRUETYPE_FONT, J('java.io.File')(str(fallback_file))).deriveFont(jpype.JFloat(em))
                selected = fallback
            assert selected.canDisplay(ord(char)), (font_file, char)
            vector = selected.createGlyphVector(context, char)
            bounds = vector.getGlyphPixelBounds(0, context, 0, 0)
            y_offset = baseline + bounds.y
            assert 0 < bounds.width <= cell_width - 2 and 0 < bounds.height <= cell_height - 2, (char, bounds)
            assert 0 <= y_offset <= line_height - bounds.height, (char, metrics, bounds)
            x, y = index % columns * cell_width + 1, index // columns * cell_height + 1
            graphics.drawGlyphVector(vector, float(x - bounds.x), float(y - bounds.y))
            advance = round(vector.getGlyphMetrics(0).getAdvanceX())
            definitions.append(f'char id={ord(char)} x={x} y={y} width={bounds.width} height={bounds.height} '
                               f'xoffset={bounds.x} yoffset={y_offset} xadvance={advance} page={page} chnl=15')
        graphics.dispose()
        assert J('javax.imageio.ImageIO').write(image, 'png', J('java.io.File')(str(output / pages[-1])))
    lines = [f'info face="GFL2 Exilium Glyph Supplement" size={em} bold=0 italic=0 charset="" unicode=1',
             f'common lineHeight={line_height} base={baseline} scaleW={atlas_size} scaleH={atlas_size} pages={len(pages)} packed=0']
    lines.extend(f'page id={index} file="{name}"' for index, name in enumerate(pages))
    lines.extend([f'chars count={len(definitions)}', *definitions])
    (output / 'fallback.fnt').write_text('\n'.join(lines) + '\n', 'ascii')


def prepare(generated):
    config = settings()
    configured = config.get('localization_fonts', {})
    source = {key: Path(configured.get(key, '')) for key in CAPTION_FIELDS}
    for key, font in source.items():
        if not font.is_file():
            raise FileNotFoundError(f'Configure localization_fonts.{key} in .local/paths.json')
    source.update(EN=source['CN'], KO=source['CN'])
    game = Path(config['game_root'])
    sys.path.insert(0, config['python_dependencies'])
    import jpype

    output = Path(generated).resolve() / '42/media/gfl2_pmx/fonts'
    assert output.resolve() == output and output.is_relative_to(BUILD.resolve())
    BUILD.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix='font-generation-', dir=BUILD) as directory:
        stage = Path(directory) / 'fonts'
        stage.mkdir()
        jpype.startJVM(str(game / 'jre64/bin/server/jvm.dll'), '--enable-native-access=ALL-UNNAMED',
                      '-Djava.awt.headless=true', '-Duser.home=' + directory, convertStrings=True)
        try:
            for language, suffix in FONT_NAMES.items():
                required = required_characters(language)
                reviewed_sizes = {}
                for scale in range(1, 5):
                    for size in ('Small', 'Medium', 'Large'):
                        font = game / f'media/fonts/{language}/{scale}x/zomboid{size}{suffix}.fnt'
                        metrics, supported = font_details(font)
                        missing = required - supported
                        previous = reviewed_sizes.get(metrics[0])
                        if previous is None:
                            reviewed_sizes[metrics[0]] = (metrics, missing)
                            if missing:
                                render_font(jpype, source[language], source['CN'], missing, metrics, stage / language / str(metrics[0]))
                        else:
                            assert previous == (metrics, missing), font
            shutil.copyfile(ROOT / 'licenses/Noto-CJK-OFL.txt', stage / 'OFL.txt')
        finally:
            jpype.shutdownJVM()
        if output.exists():
            shutil.rmtree(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        stage.rename(output)
    return output


if __name__ == '__main__':
    print(prepare(BUILD / 'generated-font-mod'))
