"""Regression check for repeated release builds using the configured game compiler."""
from pathlib import Path
import json
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build


class ReleaseBuildTests(unittest.TestCase):
    def test_removed_source_and_nested_class_stay_out_of_next_jar(self):
        config = build.settings()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = root / 'game/java'
            sources.mkdir(parents=True)
            provenance = root / 'provenance'
            provenance.mkdir()
            comparator = root / 'tools/java/CompareRuntime.java'
            comparator.parent.mkdir(parents=True)
            shutil.copyfile(build.ROOT / 'tools/java/CompareRuntime.java', comparator)
            (provenance / 'native-runtime.json').write_text(json.dumps({
                'native_hashes': {}, 'chest_hashes': {}, 'native_version': 'fixture',
            }), 'utf8')
            main = sources / 'Keep.java'
            removed = sources / 'Removed.java'
            main.write_text('class Keep { static class Old {} }', 'utf8')
            removed.write_text('class Removed {}', 'utf8')
            output = root / 'build'
            with patch.multiple(build, ROOT=root, BUILD=output), patch.object(
                build, 'settings', return_value={**config, 'reference_jar': config['zombiebuddy_jar']}
            ):
                build.build()
                jar = output / 'GFL2Exilium.jar'
                with zipfile.ZipFile(jar) as archive:
                    self.assertIn('Keep$Old.class', archive.namelist())
                    self.assertIn('Removed.class', archive.namelist())
                    cached = output / 'classes/Removed.class'
                    cached.parent.mkdir(exist_ok=True)
                    cached.write_bytes(archive.read('Removed.class'))
                removed.unlink()
                main.write_text('class Keep {}', 'utf8')
                build.build()
                with zipfile.ZipFile(jar) as archive:
                    self.assertEqual([n for n in archive.namelist() if n.endswith('.class')], ['Keep.class'])


if __name__ == '__main__':
    unittest.main()
