"""Shared paths and fingerprints for this repository's build/release commands."""
from pathlib import Path
import hashlib
import json
import os

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'


def settings():
    file = ROOT / '.local/paths.json'
    if not file.is_file():
        raise SystemExit('Copy config/paths.example.json to .local/paths.json and fill local dependency paths.')
    return json.loads(file.read_text('utf-8-sig'))


def sha(file):
    with Path(file).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(file, value):
    file = Path(file)
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', 'utf8')


def environment():
    temp = BUILD / 'tmp'
    temp.mkdir(parents=True, exist_ok=True)
    return dict(os.environ, TEMP=str(temp), TMP=str(temp))


def inventory(root):
    root = Path(root)
    return {file.relative_to(root).as_posix(): {'bytes': file.stat().st_size, 'sha256': sha(file)}
            for file in sorted(root.rglob('*')) if file.is_file()}
