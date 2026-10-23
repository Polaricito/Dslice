# CI signing key

`dslice-ci.jks` is a public, non-secret signing key used by GitHub Actions so
that every non-PR build produces an installable, consistently signed release
APK. Android rejects unsigned APKs, and a new key per CI run would force users
to uninstall the app before every update.

The key and its password are intentionally committed to this repository. The
password is public and provides no security. Do not use this key for the Play
Store or for a build whose signature is meant to be trusted.

If a private release key is needed, add the `KEYSTORE` (base64), 
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` GitHub Actions secrets, or
create a root `signing.properties` file. Those take precedence over the CI key.
