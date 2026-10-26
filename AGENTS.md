# Repository guidance

- Preserve the split between `buildthisplease-core`, `buildthisplease-compose`, and `sample`.
- Never commit credentials. A `btp_pk_…` value is publishable; Google service-account keys and release signing credentials are not.
- Production mutations must remain bound to method, path, exact body bytes, a one-time Play Integrity request hash, and the installation key signature.
- Do not introduce emulator or sideload integrity bypasses into the production client. Use `MockBuildThisPleaseClient` for development.
- Keep minSdk 24. For SDK, sample, or build-configuration changes, verify `:buildthisplease-core:testDebugUnitTest` plus `:sample:assembleDebug`; prose-only changes need content/link checks instead.

For issue creation or maintenance in this repository, follow the [app-owned checklist](https://github.com/rosday/build-this-please-brain/blob/main/Workflows/Development.md#create-and-maintain-issues). It defines owning Project, assignee, actual-state Status, target Version, start/completion checks and saved-field readback. Existing technical and action-specific approval instructions still apply; private company Brain access is not required.
