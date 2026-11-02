# Release signing and distribution

Status for v0.1.1: **OWNER LOCAL SIGNING STEPS PENDING**. On 2026-11-02 the
owner explicitly approved a new permanent production identity and committed to
enter/save passwords and confirm a secure backup locally. Creation has not yet
been confirmed; no production certificate is available. Known local signing
locations contained only development keys at inspection.
Never reuse the owner debug key as a public release key.

## Identity and recovery checkpoint

Before creating a permanent key, the owner must explicitly approve it. It becomes
the long-term update identity for both phone and watch APKs. Losing it can prevent
updates to installed builds; replacing it breaks ordinary in-place updates outside
managed signing systems. Keep an encrypted/offline backup outside this repository,
and store the password(s), alias and recovery instructions in the owner's password
manager. Never commit the keystore or its base64 encoding. Passwords must be entered
locally or through GitHub's secret UI, never supplied in chat or command history.
An existing production identity should be reused only after its public certificate
and owner-controlled recovery/backup arrangement are verified.

Both apps always use the same production certificate. Owner development installs
retain their debug identity; a production key is intentionally different and cannot
update a debug-signed install in place. Do not uninstall/clear owner apps to test
this. Perform focused physical UX acceptance with the compatible debug pair, and
signature/install checks on disposable emulators or separately authorized devices.

## Local creation — owner action, not executed

After explicit approval, run this in an owner-controlled terminal. `keytool`
interactively requests the password; record it directly in the password manager.
PKCS12 normally uses the same store/key password; record that relationship.
The public certificate uses a project name rather than personal contact details.

```sh
umask 077
mkdir -p "$HOME/.local/share/wristprompt/release"
export WRISTPROMPT_RELEASE_STORE_FILE="$HOME/.local/share/wristprompt/release/wristprompt-release.p12"
test ! -e "$WRISTPROMPT_RELEASE_STORE_FILE" && \
  "$JAVA_HOME/bin/keytool" -genkeypair -storetype PKCS12 \
    -keystore "$WRISTPROMPT_RELEASE_STORE_FILE" -alias wristprompt-release \
    -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=WristPrompt Release'
"$JAVA_HOME/bin/keytool" -list -v -keystore "$WRISTPROMPT_RELEASE_STORE_FILE" -alias wristprompt-release
```

Record only the public certificate SHA-256 fingerprint in the release audit. The
owner must confirm an independent secure backup and password-manager recovery
before enabling distribution. The signing checkpoint remains pending until then.
Test restoring a *disposable* key first; production recovery is never destructive
or automatic. Losing access requires an explicit recovery decision, not a new key
silently substituted into a release.

## Gradle contract

Both modules apply the same [signing validation](../gradle/release-signing.gradle.kts)
and configure their release variant from the same environment.
The full environment is:

- `WRISTPROMPT_RELEASE_STORE_FILE`
- `WRISTPROMPT_RELEASE_STORE_PASSWORD`
- `WRISTPROMPT_RELEASE_KEY_ALIAS`
- `WRISTPROMPT_RELEASE_KEY_PASSWORD`
- `WRISTPROMPT_REQUIRE_RELEASE_SIGNING=true` for an explicitly signed build

No settings produces the ordinary unsigned release build used by CI. Any supplied
production setting or explicit signing request requires all four values and an
existing keystore; otherwise configuration fails. There is no fallback to debug
signing. For a local signed build, load passwords from the owner's secure local
environment, then run:

```sh
WRISTPROMPT_REQUIRE_RELEASE_SIGNING=true ./gradlew clean \
  :wear:assembleRelease :mobile:assembleRelease --no-daemon --console=plain
python3 tools/verify_release.py --tag v0.1.1 \
  --certificate PUBLIC_CERTIFICATE_SHA256 --output /tmp/wristprompt-v0.1.1-verified
```

The output directory must not exist. The verifier checks exact source/app version
metadata, APK signatures, approved matching certificate, package ID, backup/
cleartext/debug policy and embedded distribution notices, then creates only the
matched APK pair and SHA256SUMS. Known owner debug identity and Android Debug
certificates are rejected. A synthetic temporary key can exercise this mechanism;
it is never a production identity or distributable owner release.

## GitHub configuration — secrets pending owner checkpoint

The `production-release` environment was created and read back on 2026-11-02:
required reviewer `grassified`, administrator bypass disabled, allowed branch
`main` and tag pattern `v*`. Self-review is allowed so the sole owner can explicitly
approve their own release run. These deployment restrictions do not create a
repository tag-protection rule; the workflow separately checks exact annotated
tag/main identity. No signing secrets or public fingerprint are configured yet.
Keep signing secrets confined to this environment, unavailable to PR validation.
After the local identity/backup checkpoint, add:

| Environment secret | Contents |
| --- | --- |
| `WRISTPROMPT_RELEASE_KEYSTORE_BASE64` | Base64 of the approved keystore |
| `WRISTPROMPT_RELEASE_STORE_PASSWORD` | Store password from password manager |
| `WRISTPROMPT_RELEASE_KEY_ALIAS` | Approved alias |
| `WRISTPROMPT_RELEASE_KEY_PASSWORD` | Key password from password manager |

Set repository variable `WRISTPROMPT_RELEASE_CERT_SHA256` to the approved public
SHA-256 certificate fingerprint (64 hexadecimal digits). It is public metadata;
secret material belongs only in Secrets. For the keystore, a direct pipe avoids
printing or writing a base64 copy; run only after the environment is protected:

```sh
base64 -w 0 "$WRISTPROMPT_RELEASE_STORE_FILE" | \
  gh secret set WRISTPROMPT_RELEASE_KEYSTORE_BASE64 --env production-release --repo grassified/WristPrompt
```

Enter other secrets in GitHub's UI using the password manager, or use the CLI's
interactive secret prompt. Do not put passwords in command arguments, logs,
repository variables, caches or workflow artifacts. Confirm secret *names*, not
values. The CI validation workflow never consumes production signing secrets.

## Controlled publication

[Signed release workflow](../.github/workflows/release.yml) accepts an annotated
`vMAJOR.MINOR.PATCH` tag on the exact current main commit (or manual dispatch naming
that tag). The release PR must first pass CI, focused owner physical acceptance
and this signing checkpoint. Do not tag while those gates are pending.

The workflow reruns tests/lint/builds/Python/isolation/links before decoding the
keystore into a private ephemeral runner file. Signing caches are disabled. Both
release APKs use the same environment identity; the verifier requires the approved
public certificate. Only verified APKs/checksums are handed off for one day; keys
are removed and never cached/uploaded. A separate write-permission job rechecks
checksums/signatures/metadata/notices and publishes through a draft. Failed uploads
remain a draft; an existing release is never overwritten automatically.

Expected assets:

- `WristPrompt-v0.1.1-mobile.apk`
- `WristPrompt-v0.1.1-wear.apk`
- `SHA256SUMS.txt`

Users install **both** APKs as a matched pair. Future upgrades retain this release
identity. Release notes link to the exact tag's Corresponding Source and build
instructions. APK `assets/legal/` retains project licensing, canonical Apache
text, embedded upstream notices and an inventory/hash of resolved production
artifacts. No dependency version or linking-exception scope is expanded.

After publication verify the tag commit, prerelease status, downloadable assets,
checksums, both signatures/version/package metadata, main CI and clean checkout.
v0.1.0 remains immutable. Source-only publication is not an automatic fallback;
it requires separate owner approval.

Primary references: [Android signing](https://developer.android.com/studio/publish/app-signing),
[GitHub secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets),
and [environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
