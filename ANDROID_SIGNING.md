# Private Android signing setup

SofaPower's update system only treats `android-v*` GitHub releases as installable updates. Those releases are built only when a private signing key is available through GitHub Actions secrets.

Never commit the `.jks` file, its Base64 form, passwords, or aliases to the public repository.

Create one release keystore locally and keep at least two offline backups. Add these repository secrets in GitHub Settings → Secrets and variables → Actions:

- `ANDROID_KEYSTORE_BASE64` — Base64 of the complete `.jks` file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then bump `versionCode`/`versionName`, push the code, and create a tag matching the version, for example `android-v1.3.0`. The workflow will build `SofaPower.apk`, sign it with the same private key, and publish it as a GitHub Release asset.

The private signing key must stay private and stable. Losing it means Android cannot install future versions over existing signed installations.
