import tempfile
import unittest
from pathlib import Path
from check_markdown_links import destinations, missing_targets


class MarkdownLinksTest(unittest.TestCase):
    def test_inline_images_references_and_escaped_names(self):
        source = '[one](a.md#heading) ![two](<space name.png>)\n[ref]: nested/b.md\n[x](a\\(b\\).md)'
        self.assertEqual(['a.md#heading', 'space name.png', 'a(b).md', 'nested/b.md'], list(destinations(source)))

    def test_fenced_examples_are_ignored(self):
        source = '```md\n[x](missing)\n```\n~~~\n[x](also-missing)\n~~~\n[ok](real.md)'
        self.assertEqual(['real.md'], list(destinations(source)))

    def test_relative_root_fragment_and_percent_encoded_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'docs').mkdir(); (root / 'a b.md').touch()
            doc = root / 'docs' / 'README.md'
            doc.write_text('[a](../a%20b.md#heading) [b](/a%20b.md) [self](#anchor) [external](https://example.invalid/x)', encoding='utf-8')
            self.assertEqual((2, []), missing_targets(root, [doc]))

    def test_missing_and_repository_escape_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); doc = root / 'README.md'
            doc.write_text('[a](missing.md) [b](../outside.md)', encoding='utf-8')
            count, missing = missing_targets(root, [doc])
            self.assertEqual(2, count); self.assertEqual(2, len(missing))

    def test_remote_schemes_and_protocol_relative_links_are_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); doc = root / 'README.md'
            doc.write_text('[a](mailto:example@example.invalid) [b](//example.invalid/x)', encoding='utf-8')
            self.assertEqual((0, []), missing_targets(root, [doc]))


if __name__ == '__main__':
    unittest.main()
