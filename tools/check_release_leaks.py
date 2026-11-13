"""Audit distributed files for private GLSL and accidental build artifacts."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile


TOKEN = re.compile(rb'//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[A-Za-z_]\w*|\d+(?:\.\d*)?(?:[eE][+-]?\d+)?|[^\s]')
WINDOW = 40
PRIVATE_SUFFIXES = {'.java', '.kt', '.py', '.log', '.map', '.pro', '.orig', '.bak'}
PRIVATE_NAMES = {'mapping.txt', 'java-mapping.txt', 'glsl-mapping.json', 'seed.bin', 'render-inputs.zip'}
ARCHIVE_SUFFIXES = {'.zip', '.jar'}


def tokens(raw):
    return tuple(match[0] for match in TOKEN.finditer(raw)
                 if not match[0].startswith((b'//', b'/*')))


def windows(source):
    for i in range(len(source) - WINDOW + 1):
        yield source[i:i + WINDOW]


def class_strings(raw):
    """Read JVM UTF-8 constant-pool entries without interpreting bytecode."""
    if raw[:4] != b'\xca\xfe\xba\xbe' or len(raw) < 10:
        raise ValueError('Invalid JVM class')
    count = int.from_bytes(raw[8:10], 'big')
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
              12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    pos = 10
    index = 1
    while index < count:
        tag = raw[pos]
        pos += 1
        if tag == 1:
            length = int.from_bytes(raw[pos:pos + 2], 'big')
            pos += 2
            if pos + length > len(raw):
                raise ValueError('Truncated JVM string')
            yield raw[pos:pos + length]
            pos += length
        else:
            pos += widths[tag]
        index += 2 if tag in (5, 6) else 1


def read_references(paths):
    for path in paths:
        if path.is_dir():
            for child in sorted(p for p in path.rglob('*') if p.is_file()):
                yield child.relative_to(path).as_posix(), child.read_bytes()
        elif zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for entry in archive.infolist():
                    if not entry.is_dir():
                        yield entry.filename, archive.read(entry)
        else:
            yield path.name, path.read_bytes()


def audit(targets, references, shared=()):
    private = {}
    for name, raw in references:
        if not raw.strip():
            continue
        name = f'{name}@{hashlib.sha256(raw).hexdigest()[:12]}'
        private[name] = tokens(raw)
    if not private or any(not source for source in private.values()):
        raise ValueError('Expected non-empty private text references')
    shared_windows = {window for _, raw in shared for window in windows(tokens(raw))}
    indexed = {}
    full_sources = {}
    for name, source in private.items():
        if len(source) >= WINDOW:
            full_sources.setdefault(source[:WINDOW], set()).add(name)
        for window in set(windows(source)) - shared_windows:
            indexed.setdefault(window, set()).add(name)
    if not indexed:
        raise ValueError('No private source fragments remain after shared declarations')
    findings = []
    scanned = dict(files=0, archives=0, texts=0, class_files=0)

    def inspect_text(label, raw):
        source = tokens(raw)
        if len(source) < WINDOW:
            return
        scanned['texts'] += 1
        fragments = set()
        candidates = set()
        for window in windows(source):
            fragments.update(indexed.get(window, ()))
            candidates.update(full_sources.get(window, ()))
        for ref in sorted(fragments | candidates):
            whole = private[ref]
            full = any(source[i:i + len(whole)] == whole for i, token in enumerate(source)
                       if token == whole[0])
            if full or ref in fragments:
                findings.append(dict(path=label, kind='whole_source' if full else 'source_fragment', reference=ref))

    def visit(label, name, raw, depth):
        scanned['files'] += 1
        path = PurePosixPath(name.replace('\\', '/').lower())
        if ('private' in path.parts or path.suffix in PRIVATE_SUFFIXES or path.name in PRIVATE_NAMES):
            findings.append(dict(path=label, kind='build_artifact'))
        if path.suffix in ARCHIVE_SUFFIXES or raw.startswith((b'PK\x03\x04', b'PK\x05\x06')):
            if depth >= 5:
                raise ValueError(f'Archive nesting too deep: {label}')
            with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                scanned['archives'] += 1
                names = [e.filename for e in archive.infolist() if not e.is_dir()]
                if len(names) != len(set(names)):
                    raise ValueError(f'Duplicate archive entries: {label}')
                for entry in names:
                    visit(label + '!' + entry, entry, archive.read(entry), depth + 1)
        elif path.suffix == '.class':
            scanned['class_files'] += 1
            for text in class_strings(raw):
                if b'\n' in text or len(text) >= 64:
                    inspect_text(label, text)
        else:
            # Include plain text saved with an arbitrary extension.
            try:
                text = raw.decode('utf-8-sig')
            except UnicodeDecodeError:
                return
            if '\x00' not in text:
                inspect_text(label, text.encode('utf8'))

    for target in targets:
        if target.is_dir():
            files = sorted(p for p in target.rglob('*') if p.is_file())
            if not files:
                raise ValueError(f'No release files under {target}')
            for file in files:
                relative = file.relative_to(target).as_posix()
                visit(str(target) + '/' + relative, relative, file.read_bytes(), 0)
        else:
            visit(str(target), target.name, target.read_bytes(), 0)
    unique = {tuple(sorted(finding.items())): finding for finding in findings}
    return dict(status='BLOCKED' if unique else 'PASS', references=len(private), window_tokens=WINDOW,
                counts=scanned, findings=list(unique.values()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('targets', nargs='+', type=Path)
    parser.add_argument('--reference', action='append', type=Path, required=True)
    parser.add_argument('--shared', action='append', type=Path, default=[])
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if any(args.out.resolve().is_relative_to(p.resolve()) for p in args.targets):
        parser.error('Write the report outside the audited package')
    try:
        report = audit(args.targets, read_references(args.reference), read_references(args.shared))
    except (OSError, ValueError, KeyError, IndexError, zipfile.BadZipFile, RuntimeError) as exc:
        report = dict(status='BLOCKED', errors=[str(exc)])
    report['target_sha256'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in args.targets if p.is_file()}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf8')
    print(f"{report['status']}: {len(report.get('findings', []))} findings. {args.out}")
    raise SystemExit(report['status'] != 'PASS')
