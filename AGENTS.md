# Repository guidance

- Preserve the split between `buildthisplease-core`, `buildthisplease-compose`, and `sample`.
- Never commit credentials. A `btp_pk_…` value is publishable; Google service-account keys and release signing credentials are not.
- Production mutations must remain bound to method, path, exact body bytes, a one-time Play Integrity request hash, and the installation key signature.
- Do not introduce emulator or sideload integrity bypasses into the production client. Use `MockBuildThisPleaseClient` for development.
- Keep minSdk 24 and verify `:buildthisplease-core:testDebugUnitTest` plus `:sample:assembleDebug` after changes.
