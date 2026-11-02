#!/usr/bin/env python3
"""Verify a matched signed release pair; emit only verified APKs and checksums.

The approved public certificate fingerprint is mandatory. Signing material is
never read here. Ordinary unsigned CI output cannot satisfy this gate.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'io.github.grassified.wristprompt'
OWNER_DEBUG = 'fd8491b6238368a04d58bd0653c35ff54a3a17fc9b31e53a6af7110579d37061'


def version(root, tag):
    if not re.fullmatch(r'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)', tag):
        raise ValueError('Expected an exact vMAJOR.MINOR.PATCH tag')
    versions = []
    for module in ('mobile', 'wear'):
        source = (root / module / 'build.gradle.kts').read_text()
        codes = re.findall(r'^\s*versionCode\s*=\s*([0-9]+)\s*$', source, re.MULTILINE)
        names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', source, re.MULTILINE)
        if len(codes) != 1 or len(names) != 1:
            raise ValueError('Ambiguous application version metadata')
        versions.append((names[0], int(codes[0])))
    if versions[0] != versions[1] or versions[0][0] != tag[1:] or versions[0][1] <= 0:
        raise ValueError('Tag and mobile/Wear version metadata must match')
    return versions[0]


def certificate(value):
    value = value.lower().replace(':', '')
    if not re.fullmatch('[0-9a-f]{64}', value) or value == OWNER_DEBUG:
        raise ValueError('An approved non-debug SHA-256 certificate is required')
    return value


def verify_badging(output, expected):
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output, re.MULTILINE)
    if not match or match.groups() != (PACKAGE, str(expected[1]), expected[0]) or 'application-debuggable' in output:
        raise ValueError('Unexpected APK package/version/debug metadata')


def verify_manifest(output):
    for name in ('allowBackup', 'usesCleartextTraffic'):
        values = re.findall(rf'A: android:{name}\([^)]*\)=\(type 0x12\)(0x[0-9a-f]+)', output)
        if values != ['0x0']:
            raise ValueError('Release manifest must disable backup and cleartext')
    for name in ('debuggable', 'testOnly'):
        values = re.findall(rf'A: android:{name}\([^)]*\)=\(type 0x12\)(0x[0-9a-f]+)', output)
        if any(value != '0x0' for value in values):
            raise ValueError('Release manifest cannot be debug/test-only')


def signer(output):
    found = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', output, re.MULTILINE)
    if len(found) != 1 or re.search(r'CN\s*=\s*Android Debug(?:,|$)', output, re.MULTILINE):
        raise ValueError('Expected exactly one non-debug APK signer')
    return certificate(found[0])


def verify_notices(apk, tag):
    required = {'LICENSE', 'LICENSE_EXCEPTION.md', 'THIRD_PARTY_NOTICES.md', 'APACHE-2.0.txt', 'INVENTORY.txt', 'SOURCE.txt'}
    with zipfile.ZipFile(apk) as archive:
        files = {name.removeprefix('assets/legal/') for name in archive.namelist() if name.startswith('assets/legal/')}
        if not required.issubset(files) or not any(name.startswith('notice-') for name in files):
            raise ValueError('APK distribution license/notice bundle is incomplete')
        expected_source = f'https://github.com/grassified/WristPrompt/tree/{tag}'
        if expected_source not in archive.read('assets/legal/SOURCE.txt').decode().split():
            raise ValueError('APK corresponding-source reference does not match the release')


def run(*args):
    result = subprocess.run([str(a) for a in args], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError('Android artifact verification command failed (output withheld)')
    return result.stdout


def verify(root, tag, fingerprint, output, sdk, artifacts=None):
    expected = version(root, tag)
    fingerprint = certificate(fingerprint)
    tools = sdk / 'build-tools' / '36.0.0'
    apks = []
    for module in ('mobile', 'wear'):
        apk = (artifacts / f'WristPrompt-{tag}-{module}.apk') if artifacts else root / module / 'build/outputs/apk/release' / f'{module}-release.apk'
        if not apk.is_file():
            raise ValueError('Signed release APK missing; unsigned artifacts are not distributable')
        if signer(run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)) != fingerprint:
            raise ValueError('APK signer does not match the approved production certificate')
        verify_badging(run(tools / 'aapt', 'dump', 'badging', apk), expected)
        verify_manifest(run(tools / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml'))
        verify_notices(apk, tag)
        # Never permit this build's ephemeral debug identity as production signing.
        debug = root / module / 'build/outputs/apk/debug' / f'{module}-debug.apk'
        if debug.exists():
            debug_output = run(tools / 'apksigner', 'verify', '--print-certs', debug)
            if f'certificate SHA-256 digest: {fingerprint}' in debug_output:
                raise ValueError('Production and debug signing identities must differ')
        apks.append((module, apk))
    output.mkdir(parents=True, exist_ok=False)  # Do not silently mix/overwrite old distribution output.
    sums = []
    for module, apk in apks:
        name = f'WristPrompt-{tag}-{module}.apk'
        destination = output / name
        shutil.copyfile(apk, destination)
        sums.append(f'{hashlib.sha256(destination.read_bytes()).hexdigest()}  {name}')
    (output / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n')
    print(f'PASS: {tag}, versionCode {expected[1]}, matched APK signatures; certificate SHA-256 {fingerprint}')
    print('\n'.join(sums))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--check-version', action='store_true')
    parser.add_argument('--certificate', default=os.environ.get('WRISTPROMPT_RELEASE_CERT_SHA256'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.check_version:
        name, code = version(ROOT, args.tag)
        print(f'PASS: matched versionName {name}, versionCode {code}')
    else:
        if not args.certificate or not args.output or not os.environ.get('ANDROID_HOME'):
            parser.error('certificate, output and ANDROID_HOME are required')
        verify(ROOT, args.tag, args.certificate, args.output, Path(os.environ['ANDROID_HOME']), args.artifacts)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        raise SystemExit(f'FAIL: {error}')
