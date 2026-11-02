from pathlib import Path
import tempfile
import unittest
import zipfile
from verify_release import certificate, version, verify_badging, verify_manifest, verify_notices, signer, OWNER_DEBUG, PACKAGE


class ReleaseVerificationTest(unittest.TestCase):
    def test_version_requires_consistent_modules_and_exact_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module in ('mobile', 'wear'):
                (root/module).mkdir()
                (root/module/'build.gradle.kts').write_text('versionCode = 2\nversionName = "0.1.1"\n')
            self.assertEqual(('0.1.1', 2), version(root, 'v0.1.1'))
            for tag in ('v0.1.0', 'v01.1.1', '0.1.1', 'v0.1.1; echo secret'):
                with self.assertRaises(ValueError): version(root, tag)
            (root/'wear/build.gradle.kts').write_text('versionCode = 1\nversionName = "0.1.1"\n')
            with self.assertRaises(ValueError): version(root, 'v0.1.1')

    def test_approved_certificate_must_be_sha256_and_not_owner_debug(self):
        self.assertEqual('a'*64, certificate(':'.join(['AA']*32)))
        for value in ('', 'g'*64, 'a'*63, OWNER_DEBUG):
            with self.assertRaises(ValueError): certificate(value)

    def test_signer_rejects_debug_multiple_and_missing_signers(self):
        valid = 'Signer #1 certificate DN: CN=Synthetic\nSigner #1 certificate SHA-256 digest: ' + 'a'*64 + '\n'
        self.assertEqual('a'*64, signer(valid))
        for output in ('', valid.replace('CN=Synthetic', 'CN=Android Debug'), valid + valid.replace('#1', '#2')):
            with self.assertRaises(ValueError): signer(output)

    def test_badging_rejects_wrong_package_version_or_debug(self):
        valid = f"package: name='{PACKAGE}' versionCode='2' versionName='0.1.1' platformBuildVersionName='16'\n"
        verify_badging(valid, ('0.1.1', 2))
        for output in (valid.replace(PACKAGE, 'foreign.package'), valid.replace("versionCode='2'", "versionCode='1'"),
                       valid + 'application-debuggable', ''):
            with self.assertRaises(ValueError): verify_badging(output, ('0.1.1', 2))

    def test_release_manifest_must_be_private_and_non_debug(self):
        valid = 'A: android:allowBackup(0x01010280)=(type 0x12)0x0\nA: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0'
        verify_manifest(valid)
        for output in ('', valid.replace('0x0', '0xffffffff'), valid+'\nA: android:debuggable(0x1)=(type 0x12)0xffffffff',
                       valid+'\nA: android:testOnly(0x1)=(type 0x12)0xffffffff'):
            with self.assertRaises(ValueError): verify_manifest(output)

    def test_missing_or_wrong_release_license_bundle_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory)/'candidate.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/legal/LICENSE', 'synthetic')
            with self.assertRaises(ValueError): verify_notices(apk, 'v0.1.1')
            with zipfile.ZipFile(apk, 'w') as archive:
                for name in ('LICENSE', 'LICENSE_EXCEPTION.md', 'THIRD_PARTY_NOTICES.md', 'APACHE-2.0.txt', 'INVENTORY.txt', 'notice-0001.txt'):
                    archive.writestr('assets/legal/'+name, 'synthetic')
                archive.writestr('assets/legal/SOURCE.txt', 'https://example.invalid/tree/v0.1.0')
            with self.assertRaises(ValueError): verify_notices(apk, 'v0.1.1')


if __name__ == '__main__':
    unittest.main()
