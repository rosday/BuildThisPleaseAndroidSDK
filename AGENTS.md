# Repository guidance

- Preserve the split between `buildthisplease-core`, `buildthisplease-compose`, and `sample`.
- Never commit credentials. A `btp_pk_…` value is publishable; Google service-account keys and release signing credentials are not.
- Production mutations must remain bound to method, path, exact body bytes, a one-time Play Integrity request hash, and the installation key signature.
- Do not introduce emulator or sideload integrity bypasses into the production client. Use `MockBuildThisPleaseClient` for development.
- Keep minSdk 24. For SDK, sample, or build-configuration changes, verify `:buildthisplease-core:testDebugUnitTest` plus `:sample:assembleDebug`; prose-only changes need content/link checks instead.

Issue reporting is open to public contributors through this repository's GitHub Issues. Describe the SDK version, platform, reproduction steps, expected and actual behavior, and sanitized logs; omit credentials and private customer or company material. A local draft containing those details is sufficient when GitHub access is unavailable.

Authorized maintainers with existing private app Brain access follow the [app-owned checklist](https://github.com/rosday/build-this-please-brain/blob/main/Workflows/Development.md#create-and-maintain-issues) for Project membership, assignment, Status, Version and saved-field verification. Public contributors do not need private Brain access and are not responsible for maintaining those fields. Maintainers handle that triage; do not copy private checklist content into public issues or expand access. Routine SDK builds and integration work can proceed without private Project maintenance. Existing technical and action-specific approval instructions still apply.
