# Signing and GitHub releases

The release build is signed with a private keystore that is **not** committed to this
repository. Gradle reads the signing configuration from the usual project properties:

```properties
keystoreFile=/absolute/path/to/kizuna-release.jks
keystorePassword=...
keystoreAlias=kizuna
keystoreAliasPassword=...
```

For local builds these can be placed in `~/.gradle/gradle.properties`.

## GitHub Actions secrets

The `Build and Release` workflow expects these repository secrets:

| Secret | Value |
| --- | --- |
| `KIZUNA_KEYSTORE_BASE64` | Base64 encoding of the PKCS12 keystore |
| `KIZUNA_KEYSTORE_PASSWORD` | Keystore password |
| `KIZUNA_KEY_ALIAS` | Key alias (`kizuna`) |
| `KIZUNA_KEY_PASSWORD` | Key password |

Example of setting them with the GitHub CLI:

```sh
base64 -w0 kizuna-release.jks | gh secret set KIZUNA_KEYSTORE_BASE64
gh secret set KIZUNA_KEYSTORE_PASSWORD --body '...'
gh secret set KIZUNA_KEY_ALIAS --body 'kizuna'
gh secret set KIZUNA_KEY_PASSWORD --body '...'
```

## Releases

`.github/workflows/build.yaml` is a `workflow_dispatch` (manual) workflow. It builds
four signed `UiOvpn23Release` APKs (arm64-v8a, armeabi-v7a, x86 and x86_64),
verifies all signatures, and publishes them to a GitHub release whose tag is the build time
precise to the minute (`YYYY.MM.DD-HHMM`, Asia/Shanghai). The app's About screen
shows the build time from `BuildConfig.BUILD_TIME`.
