#!/usr/bin/env python3
"""Rebuild the bundled FanQie provider against this module's public association API."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module-jar', required=True, type=Path,
                        help='app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar')
    parser.add_argument('--android-jar', required=True, type=Path)
    parser.add_argument('--d8-jar', required=True, type=Path, help='Android SDK build-tools/<version>/lib/d8.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'source-files/fanqie.rmsource')
    args = parser.parse_args()
    for path in (args.module_jar, args.android_jar, args.d8_jar):
        if not path.is_file():
            parser.error(f'File not found: {path}')
    source_dir = ROOT / 'source-files/fanqie'
    sources = sorted((source_dir / 'src').rglob('*.java'))
    if not sources:
        parser.error('No FanQie provider sources')
    manifest_bytes = (source_dir / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    assert manifest['apiVersion'] == 1 and manifest['id'] == 'fanqie'
    with tempfile.TemporaryDirectory(prefix='reamicro-fanqie-') as temp:
        work = Path(temp)
        classes, dex = work / 'classes', work / 'dex'
        classes.mkdir()
        dex.mkdir()
        subprocess.run([
            'javac', '-encoding', 'UTF-8', '--release', '8',
            '-classpath', os.pathsep.join(map(str, (args.module_jar, args.android_jar))),
            '-d', str(classes), *map(str, sources),
        ], check=True)
        subprocess.run([
            'java', '-cp', str(args.d8_jar), 'com.android.tools.r8.D8',
            '--release', '--min-api', '26', '--lib', str(args.android_jar),
            '--classpath', str(args.module_jar), '--output', str(dex),
            *map(str, sorted(classes.rglob('*.class'))),
        ], check=True)
        compiled = (dex / 'classes.dex').read_bytes()
        assert compiled.startswith(b'dex\n')
        archive_path = work / 'fanqie.rmsource'
        with zipfile.ZipFile(archive_path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in [('classes.dex', compiled), ('manifest.json', manifest_bytes)]:
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, data)
        args.output.parent.mkdir(parents=True, exist_ok=True)

        pending = args.output.with_suffix('.rmsource.tmp')
        pending.write_bytes(archive_path.read_bytes())
        pending.replace(args.output)
    print('Built:', args.output)
    print('Version:', manifest['version'])
    print('SHA256:', hashlib.sha256(args.output.read_bytes()).hexdigest())

if __name__ == '__main__':
    main()
