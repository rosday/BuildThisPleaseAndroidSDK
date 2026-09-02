# Security model

## What the SDK protects

Every production installation creates a non-exportable P-256 signing key in Android Keystore. Registration, session recovery, and each state-changing request require both:

- a fresh Google Play Integrity Standard verdict bound to a one-time server request hash; and
- a signature from that installation key over the same request hash.

The server additionally binds mutations to the exact HTTP method, path, and raw body hash, consumes each challenge once, checks a short expiry, requires `PLAY_RECOGNIZED` and `MEETS_DEVICE_INTEGRITY`, checks the exact package and Play signing certificate, and can require `LICENSED`.

Sessions are encrypted using an AES-GCM key held by Android Keystore and written under `noBackupFilesDir`. Installation signing keys and sessions therefore do not migrate through Android backup.

## Deliberate non-secrets

- project key (`btp_pk_…`)
- API base URL
- package name, signing-certificate digest, and Cloud project number

These identify an app but do not grant dashboard or administrative access.

## Never ship

- the Google service-account private key
- Cloudflare/D1 credentials
- administrator sessions or API tokens
- release-keystore passwords

Google token decoding occurs only on the backend. Store the service-account private key as a backend secret and rotate it after suspected exposure.

## Development

Use `MockBuildThisPleaseClient` for previews, emulators, and ordinary local UI work. Do not add a production bypass for Play Integrity. Test the real path with a Play internal-testing build signed through Play App Signing.

## User data

Feature requests and messages are user-generated content. Support email, RevenueCat App User ID, and subscription status are optional and must only be supplied when the host app intentionally chooses to share them. The request form never sends an empty email field, and optional registration values are omitted rather than encoded as JSON `null`. Document the fields used by the host app in Google Play Data Safety and its privacy policy.

## Incident controls

The dashboard can deactivate an Android identity or revoke an installation. Deactivation blocks new challenges and protected requests. Keep separate development and production identities and remove retired signing certificates after rollout is complete.
