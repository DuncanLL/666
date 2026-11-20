"""Compare a full Steam subscription install against this checkout and its release receipt."""
import argparse
import json
from pathlib import Path

from common import ROOT, sha


def read(path):
    return json.loads(path.read_bytes())


def check_mod(name, installed, source, assets, jar_hash=None):
    owned = {p.relative_to(source).as_posix(): p for p in source.rglob('*')
             if p.is_file() and p.name != '.gitkeep'}
    expected = assets.keys() | owned.keys()
    jar = '42/media/java/client/GFL2Exilium.jar'
    if jar_hash:
        expected.add(jar)
    actual = {p.relative_to(installed).as_posix() for p in installed.rglob('*') if p.is_file()}
    for key in sorted(expected - actual):
        yield f'{name}/{key} missing'
    for key in sorted(actual - expected):
        yield f'{name}/{key} unexpected'
    for key in sorted(expected & actual):
        path = installed / key
        if key in assets:
            entry = assets[key]
            if path.stat().st_size != entry['bytes'] or sha(path) != entry['sha256']:
                yield f'{name}/{key} differs from the resource manifest'
        if key in owned:
            # Git and Steam can use different line endings for the same source text.
            if owned[key].read_bytes().replace(b'\r\n', b'\n') != path.read_bytes().replace(b'\r\n', b'\n'):
                yield f'{name}/{key} differs from source'
        if key == jar and jar_hash and sha(path) != jar_hash:
            yield f'{name}/{key} differs from the release receipt'


def check(content):
    core = ROOT / 'game/mod'
    version, = [line.split('=', 1)[1] for line in (core / '42/mod.info').read_text('utf-8-sig').splitlines()
                if line.startswith('modversion=')]
    receipt = read(ROOT / 'provenance/releases' / f'{version}-workshop-public.json')
    items = read(ROOT / 'provenance/workshop-items.json')
    assets = read(ROOT / 'resources/manifest.json')
    if items['framework'] != receipt['framework'] or items['roles'].keys() != assets['character_packs'].keys():
        raise SystemExit('Workshop item index disagrees with the release or resource manifest')
    name = 'GFL2Exilium'
    errors = list(check_mod(name, content / str(receipt['framework']) / 'mods' / name,
                            core, assets['files'], receipt['framework_jar_sha256']))
    roles = items['roles']
    for name, item in roles.items():
        folder = content / str(item) / 'mods'
        if not folder.is_dir() or {p.name for p in folder.iterdir() if p.is_dir()} != {name}:
            errors.append(f'{folder}: expected character package {name}')
            continue
        source = ROOT / 'game/packs' / name
        if not source.is_dir():
            errors.append(f'{item}: missing character source for {name}')
        published = receipt.get('roles', {}).get(name)
        if published and published['item'] != item:
            errors.append(f'{name}: item ID differs from the release receipt')
        errors.extend(check_mod(name, folder / name, source, assets['character_packs'][name]['files']))
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Workshop {version}: framework JAR, source files and external resources match for the framework and {len(roles)} character packages.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('content', type=Path, help='Steam library directory steamapps/workshop/content/108600')
    check(parser.parse_args().content.resolve(strict=True))
