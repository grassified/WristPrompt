#!/usr/bin/env python3
"""Check repository-local Markdown link/image targets without network access.

Checks inline and reference-style destinations; skips fenced examples, external
schemes and same-document anchors. Fragment semantics are left to GitHub.
"""
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
INLINE = re.compile(r'!?\[[^\]\n]*\]\((<[^>\n]+>|(?:\\.|[^\s)\\])+)(?:\s+"[^"]*")?\)')
REFERENCE = re.compile(r'^\s{0,3}\[[^\]]+\]:\s*(<[^>]+>|\S+)', re.MULTILINE)


def destinations(source):
    lines = []
    fence = None
    for line in source.splitlines():
        match = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if match:
            marker = match[1]
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            lines.append('')
        else:
            lines.append(line if fence is None else '')
    body = '\n'.join(lines)
    for pattern in (INLINE, REFERENCE):
        for match in pattern.finditer(body):
            yield re.sub(r'\\([\\() ])', r'\1', match[1].removeprefix('<').removesuffix('>'))


def missing_targets(root, documents):
    checked = 0
    missing = []
    for document in documents:
        for destination in destinations(document.read_text(encoding='utf-8')):
            parsed = urlsplit(destination)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            checked += 1
            path = unquote(parsed.path)
            target = ((root / path.lstrip('/')) if path.startswith('/') else document.parent / path).resolve()
            if not target.is_relative_to(root.resolve()) or not target.exists():
                missing.append(f'{document.relative_to(root)}: {destination}')
    return checked, missing


def main():
    tracked = subprocess.check_output(['git', 'ls-files', '-z', '*.md'], cwd=ROOT).decode().split('\0')
    documents = [ROOT / name for name in tracked if name]
    count, missing = missing_targets(ROOT, documents)
    if missing:
        raise SystemExit('FAIL: missing/escaping local Markdown targets:\n' + '\n'.join(missing))
    print(f'PASS: {count} local Markdown targets in {len(documents)} tracked documents (fragments not checked)')


if __name__ == '__main__':
    main()
