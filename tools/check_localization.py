"""Check the game's translation reader, glyph coverage and caption layout."""
from pathlib import Path
import json
import os
import re
import sys
import tempfile
from common import ROOT, settings

LANGUAGES = ('CN', 'EN', 'JP', 'KO')
mod = ROOT / 'game/mod/42'
media = mod / 'media'
catalog = json.loads((media / 'ui/GFL2ReadyRoom/catalog.json').read_text('utf-8'))
required = {'UI_GFL2_' + key for key in catalog['text']}
pack_ids = {row['pack'] for row in catalog['models'].values()}
characters = {row['character'] for row in catalog['models'].values()}
role_keys = {'UI_GFL2_Role_' + pack for pack in pack_ids}
outfit_keys = {'UI_GFL2_Outfit_' + model for model in catalog['models']}
outfit_description_keys = {'UI_GFL2_OutfitDescription_' + model for model in catalog['models']}
provenance = ROOT/'provenance/localization'
doll_names = json.loads((provenance/'official-dolls.json').read_text('utf8'))
outfit_ids = json.loads((provenance/'official-outfit-ids.json').read_text('utf8'))['outfits']
outfit_names = json.loads((provenance/'published-outfit-names.json').read_text('utf8'))
weapon_names = json.loads((provenance/'weapon-names.json').read_text('utf8'))
paint_names = json.loads((provenance/'weapon-paint-names.json').read_text('utf8'))
assert paint_names.keys() == catalog['weaponStyle']['skins'].keys()
signature_definitions = (media/'lua/shared/GFL2SignatureWeapon.lua').read_text('utf8')
weapon_keys = dict(re.findall(r'id\s*=\s*"(GFL2\.\w+)",.*?name\s*=\s*"(ContextMenu_GFL2_\w+)"',
                              signature_definitions, re.S))
assert len(weapon_keys) == len(pack_ids)
assert all(catalog['roomCharacterDescriptions'].get(character) for character in characters)
assert all(catalog['roomRecruitment'][character].get(field)
           for character in characters for field in ('element', 'tags'))
assert all(catalog['skinDescriptions'].get(model)
           or catalog['skinDescriptions'].get(row.get('card', ''))
           for model, row in catalog['models'].items())
biography_keys = {'UI_GFL2_Biography_' + pack for pack in pack_ids}
recruitment_keys = {'UI_GFL2_Recruit' + field + '_' + pack
                    for pack in pack_ids for field in ('Phase', 'Tags')}
facial_source = (ROOT/'game/java/gfl2/motion/FacialExpressions.java').read_text('utf8')
facial_keys = {
    'UI_GFL2_ExpressionControl_' + label
    for controls in re.findall(r'add\(result, indices, "Expression\w+",(.*?)\);', facial_source, re.S)
    for label in re.findall(r'"([^"\r\n]*)"', controls)[1::2]
}
assert facial_keys
base = media / 'lua/shared/Translate'
context_keys = set(json.loads((base / 'CN/ContextMenu.json').read_text('utf-8')))


def missing_glyphs(font, strings):
    characters = {char for string in strings for char in string if not char.isspace()}
    return sorted(char for char in characters if font.getWidth(char, True) == 0)

config = settings()
game = Path(config['game_root'])
sys.path.insert(0, config['python_dependencies'])
import jpype

os.chdir(game)
temporary_home = tempfile.TemporaryDirectory(prefix='gfl2-localization-fonts-')
jpype.startJVM(str(game / 'jre64/bin/server/jvm.dll'), '--enable-native-access=ALL-UNNAMED',
    '-Djava.awt.headless=true', '-Duser.home=' + temporary_home.name,
    classpath=[str(game / 'projectzomboid.jar')], convertStrings=True)
try:
    J = jpype.JClass
    filesystem = J('zombie.ZomboidFileSystem').instance
    filesystem.setCacheDir(temporary_home.name)
    filesystem.init()
    bitmap_font = J('zombie.core.fonts.AngelCodeFont')
    font_manager = J('zombie.ui.TextManager').instance
    small = J('zombie.ui.UIFont').Small
    text_draw = J('zombie.ui.TextDrawObject')
    platform = J('se.krka.kahlua.j2se.J2SEPlatform').getInstance()
    env = platform.newEnvironment()
    thread = J('se.krka.kahlua.vm.KahluaThread')(platform, env)
    thread.debugOwnerThread = J('java.lang.Thread').currentThread()
    compiler = J('se.krka.kahlua.luaj.compiler.LuaCompiler')
    lua_args = jpype.JArray(J('java.lang.Object'))

    def measure_caption(frame, _):
        frame.push(float(font_manager.MeasureStringX(small, str(frame.get(0)))))
        return 1

    env.rawset('measureCaption', jpype.JProxy('se.krka.kahlua.vm.JavaFunction', dict(call=measure_caption)))
    setup = ('UIFont={Small="small"}; function getTextManager() '
             'return {MeasureStringX=function(_,_,text)return measureCaption(text) end} end\n')
    layout_path = media/'lua/client/GFL2Voice/CaptionLayout.lua'
    loaded = thread.pcall(compiler.loadstring(setup + layout_path.read_text('utf-8'), layout_path.name, env), lua_args(0))
    assert loaded[0], list(loaded)
    wrap_caption = loaded[1]

    def caption_width_limits(font, captions):
        font_manager.enumToFont[small.ordinal()] = font
        caption_draw = text_draw()
        caption_draw.setSettings(False, False, False, False, False, True)
        largest = {640: 0, 1280: 0}
        for caption in captions:
            measured = font_manager.MeasureStringX(small, caption)
            caption_draw.ReadString(small, caption, 75)
            original_width = caption_draw.getWidth()
            for viewport in largest:
                width = original_width
                if measured > viewport - 32:
                    result = thread.pcall(wrap_caption, lua_args([caption, float(viewport - 32)]))
                    assert result[0], list(result)
                    wrapped = str(result[1])
                    assert ''.join(wrapped.split()) == ''.join(caption.split()), (caption, wrapped)
                    caption_draw.ReadString(small, wrapped, 75)
                    width = caption_draw.getWidth()
                # The native layout includes the final glyph's advance, unlike MeasureStringX.
                assert width <= viewport, (viewport, width, ascii(caption))
                largest[viewport] = max(largest[viewport], width)
        return largest

    translator = J('zombie.core.Translator')
    locale = J('zombie.core.Language')
    string = J('java.lang.String').class_
    function = J('java.util.function.Function')
    make_language = locale.class_.getDeclaredConstructor(string, string, string, J('java.lang.Boolean').TYPE)
    make_language.setAccessible(True)
    read_catalog = translator.class_.getDeclaredMethod('tryFillMapFromFile',
        string, string, J('java.util.Map').class_, locale.class_, function.class_)
    read_catalog.setAccessible(True)
    identity = jpype.JProxy(function, dict(apply=lambda value: value))

    def load(lang, category, language, module=mod):
        expected = json.loads((module/'media/lua/shared/Translate'/lang/f'{category}.json').read_text('utf-8'))
        translated = J('java.util.HashMap')()
        read_catalog.invoke(None, str(module), category, translated, language, identity)
        assert dict(translated) == expected, (lang, category)
        return expected

    data_packs = ROOT/'game/packs'
    packs = sorted(pack_ids)
    chinese_ui = json.loads((base/'CN/UI.json').read_text('utf8'))
    notice_keys = {key for key in chinese_ui if key.startswith('UI_GFL2_Notice_')}
    part_group_keys = {key for key in chinese_ui if key.startswith('UI_GFL2_PartGroup_')}
    part_option_keys = {key for key in chinese_ui if key.startswith('UI_GFL2_PartOption_')}
    for control_file in data_packs.rglob('model.controls.json'):
        controls = json.loads(control_file.read_text('utf-8-sig'))['controls']
        for group in controls.get('groups', []):
            label = group.get('label', group['id'])
            category = catalog['categories'].get(label, label)
            assert 'UI_GFL2_PartGroup_'+category in part_group_keys, (control_file, label)
            for option in group['options']:
                label = option.get('label', option['id'])
                assert 'UI_GFL2_PartOption_'+label in part_option_keys, (control_file, label)
    voice_keys = {}
    embedded_subtitles = {}
    embedded_caption_text = {'CN': [], 'JP': []}
    for pack in packs:
        media_path = data_packs/pack/'42/media'
        declarations = [
            sound for file in (media_path/'scripts').glob('*Voice_Sounds.txt')
            if not file.name.endswith('NativeVoice_Sounds.txt')
            for sound in re.findall(r'^\s*sound\s+(\w+)', file.read_text('utf-8-sig'), re.M)
        ]
        clips = [
            (cue, fields) for file in (media_path/'lua').rglob('*.lua') if 'Voice' in file.as_posix()
            for cue, fields in re.findall(r'\["(\w+)"\]\s*=\s*\{([^{}]*\bjapanese\s*=\s*"[^"]+"[^{}]*)\}',
                                          file.read_text('utf-8-sig'))
        ]
        assert len(declarations) == len(set(declarations)) == len(clips) == 173, (pack,len(declarations),len(clips))
        clip_sounds = set()
        for cue, fields in clips:
            matches = [sound for sound in declarations if sound.endswith('_'+cue)]
            assert len(matches) == 1, (pack, cue, matches)
            literal = re.search(r'\bsound\s*=\s*"([^"]+)"', fields)
            assert literal is None or literal[1] == matches[0], (pack, cue, matches)
            clip_sounds.add(matches[0])
            for lang, field in (('CN', 'chinese'), ('JP', 'japanese')):
                encoded = re.search(r'\b'+field+r'\s*=\s*"([^"]+)"', fields)
                assert encoded, (pack, cue, lang)
                utf8_bytes = re.findall(r'\\([0-9]{3})', encoded[1])
                assert len(encoded[1]) == len(utf8_bytes)*4, (pack, cue, lang)
                embedded_caption_text[lang].append(bytes(map(int, utf8_bytes)).decode('utf8'))
        assert set(declarations) == clip_sounds, pack
        voice_keys[pack] = {'IGUI_GFL2_Subtitle_'+sound for sound in clip_sounds}
        embedded_subtitles[pack] = len(clips)

    for lang in LANGUAGES:
        language = make_language.newInstance(lang, lang, 'EN', jpype.JBoolean(False))
        ui = load(lang, 'UI', language)
        menu = load(lang, 'ContextMenu', language)
        assert required | notice_keys <= ui.keys(), (lang, sorted((required | notice_keys)-ui.keys()))
        assert role_keys <= ui.keys(), (lang, sorted(role_keys-ui.keys()))
        if lang != 'CN':
            assert biography_keys | recruitment_keys | outfit_description_keys <= ui.keys(), lang
        assert (part_group_keys | part_option_keys) <= ui.keys(), lang
        assert facial_keys == {key for key in ui if key.startswith('UI_GFL2_ExpressionControl_')}, lang
        assert all(ui[key].strip() for key in facial_keys), lang
        assert outfit_keys <= ui.keys(), (lang, sorted(outfit_keys-ui.keys()))
        assert menu.keys() == context_keys, (lang, sorted(context_keys-menu.keys()))
        provisional_outfits = []
        for model, record in outfit_names.items():
            assert record['clothes_id'] == outfit_ids[model]['clothes_id'], model
            published = record['published']
            provisional = record.get('provisional', {})
            assert not published.keys() & provisional.keys(), model
            title = published.get(lang) or provisional.get(lang)
            if title:
                assert ui['UI_GFL2_Outfit_'+model] == title['name'], (lang, model)
            if lang in provisional:
                provisional_outfits.append(model)
        for weapon, record in weapon_names.items():
            if lang in record:
                assert menu[weapon_keys[weapon]] == record[lang]['name'], (lang, weapon)
        for paint, record in paint_names.items():
            assert ui['UI_GFL2_WeaponSkin_'+paint] == record[lang]['name'], (lang, paint)
        external = 0
        complete_packs = []
        caption_counts = {}
        caption_text = list(embedded_caption_text.get(lang, []))
        inventory_names = {}
        for pack in packs:
            module = data_packs/pack/'42'
            items = load(lang, 'ItemName', language, module)
            expected_items = json.loads((module/'media/lua/shared/Translate/CN/ItemName.json').read_text('utf8'))
            assert items.keys() == expected_items.keys(), (lang, pack)
            assert not items.keys() & inventory_names.keys(), (lang, pack)
            for item in items.keys() & weapon_keys.keys():
                assert items[item] == menu[weapon_keys[item]], (lang, pack, item)
            inventory_names.update(items)
            subtitle_file = module/'media/lua/shared/Translate'/lang/'IG_UI.json'
            if subtitle_file.is_file():
                subtitles = load(lang, 'IG_UI', language, module)
                keys = {key for key in subtitles if key.startswith('IGUI_GFL2_Subtitle_')}
                assert keys <= voice_keys[pack], (lang, pack, sorted(keys-voice_keys[pack]))
                assert all(subtitles[key].strip() for key in keys), (lang, pack)
                caption_text.extend(subtitles[key] for key in keys)
                assert len(keys) <= embedded_subtitles[pack], (lang, pack)
                if len(keys) == embedded_subtitles[pack]:
                    complete_packs.append(pack)
                caption_counts[pack] = len(keys)
                external += len(keys)
        assert weapon_keys.keys() <= inventory_names.keys(), lang
        if lang in ('EN','KO'):
            assert set(complete_packs) == pack_ids, (lang, sorted(pack_ids - set(complete_packs)))
        print(json.dumps(dict(locale=lang, embedded_japanese_lines=sum(embedded_subtitles.values()) if lang=='JP' else 0,
                              translated_voice_lines=external, voice_packs_complete=complete_packs,
                              caption_counts=caption_counts)))
        font_directory = Path('media/fonts') / lang
        font_definitions = (font_directory / 'fonts.txt').read_text('utf-8-sig')
        small_font_name = re.search(r'font Small\s*\{\s*fnt\s*=\s*([^,]+)', font_definitions)[1].strip()
        ui_text = [*ui.values(), *menu.values(), *inventory_names.values()]
        ui_glyph_gaps = set()
        caption_glyph_gaps = set()
        line_heights = {}
        caption_widths = {}
        for scale in range(1, 5):
            font = bitmap_font((font_directory / f'{scale}x' / small_font_name).as_posix(), '')
            line_heights[scale] = font.getLineHeight()
            assert line_heights[scale] > 0 and font.getWidth('ABC') > 0, (lang, scale)
            ui_glyph_gaps.update(missing_glyphs(font, ui_text))
            caption_glyph_gaps.update(missing_glyphs(font, caption_text))
            caption_widths[scale] = caption_width_limits(font, caption_text)
        if lang in ('EN', 'KO'):
            brand_glyphs = set(menu['ContextMenu_GFL2Character_Menu'])
            assert not (ui_glyph_gaps - brand_glyphs), (lang, sorted(ui_glyph_gaps))
            assert not caption_glyph_gaps, (lang, sorted(caption_glyph_gaps))
        provisional_roles = sorted(pack for pack, details in doll_names.items() if lang in details.get('provisional', {}))
        if lang != 'CN':
            assert doll_names.keys() == pack_ids
            for pack, detail in doll_names.items():
                name = detail['published'].get(lang) or detail.get('provisional', {}).get(lang)
                assert name and ui['UI_GFL2_Role_'+pack] == name['name'], (lang, pack)
        print(json.dumps(dict(locale=lang, room_ui=len(required), role_names=len(role_keys & ui.keys()),
            missing_role_names=sorted(role_keys-ui.keys()), provisional_role_names=provisional_roles,
            outfit_names=len(outfit_keys & ui.keys()), provisional_outfit_names=len(provisional_outfits),
            outfit_descriptions=len(outfit_description_keys & ui.keys()),
            biographies=len(biography_keys & ui.keys()), recruitment_fields=len(recruitment_keys & ui.keys()),
            catalog_backed_biographies=len(characters) if lang=='CN' else 0,
            catalog_backed_outfit_descriptions=len(catalog['models']) if lang=='CN' else 0,
            catalog_backed_recruitment_fields=2*len(characters) if lang=='CN' else 0,
            runtime_notices=len(notice_keys), context_translated=len(menu), inventory_names=len(inventory_names),
            weapon_paint_names=len(paint_names), part_groups=len(part_group_keys), part_options=len(part_option_keys),
            expression_controls=len(facial_keys),
            missing_context=len(context_keys-menu.keys()), small_font_line_heights=line_heights,
            caption_max_width_pixels=caption_widths,
            small_font_glyph_gaps=sorted(ui_glyph_gaps), caption_small_font_glyph_gaps=sorted(caption_glyph_gaps)),
            ensure_ascii=False))
finally:
    jpype.shutdownJVM()
    temporary_home.cleanup()
