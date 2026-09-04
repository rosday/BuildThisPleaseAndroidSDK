# BuildThisPlease Android SDK

Reusable Android client and Jetpack Compose UI for the BuildThisPlease feature-request system. It shares the same projects, tickets, comments, votes, dashboard, and D1 database as the iOS SDK.

The Compose surface mirrors the iOS workflow: counted title and description fields, optional support email, Requests/Mine/Done sections, status-aware voting, implemented release notes, installation-owned comment editing, locked conversations, adaptive large-screen navigation, pull-to-refresh, loading and empty states, and retry-safe mutations.

## Modules

- `buildthisplease-core`: API client, encrypted installation session storage, Android Keystore signing, and Google Play Integrity proofs.
- `buildthisplease-compose`: ready-to-use feature-request board and conversation UI.
- `sample`: runnable mock-backed demonstration; no account or backend is required.

## Local integration

Add the SDK as a composite build in the consuming app's `settings.gradle.kts`:

```kotlin
includeBuild("../BuildThisPleaseAndroidSDK")
```

Then add:

```kotlin
    implementation("io.github.rosday:buildthisplease-compose:0.1.3")
```

Create one client at application scope and display it from Compose:

```kotlin
val client = BuildThisPleaseClient(
    context = applicationContext,
    configuration = BuildThisPleaseConfiguration(
        baseUrl = "https://feedback.keinois.com",
        projectKey = "btp_pk_your_publishable_project_key",
        environment = BuildThisPleaseConfiguration.Environment.PRODUCTION,
    ),
)

BuildThisPleaseFeedback(client = client, onBack = { /* navigate back */ })
```

`BuildThisPleaseFeedback` is the standalone surface and owns its list, create, and detail navigation. Apps that already use Navigation Compose can instead place the route-specific surfaces in their own graph:

The standalone surface can also hand just those two route decisions to its host by setting
`onOpenRequest` and/or `onCreateRequest`; omitted callbacks keep the built-in navigation.

```kotlin
BuildThisPleaseFeedbackList(
    client = client,
    stateKey = "feedback",
    onOpenTicket = { ticketId -> navController.navigate("feedback/$ticketId") },
    onCreateRequest = { navController.navigate("feedback/new") },
)

BuildThisPleaseTicketDetail(
    client = client,
    ticketId = ticketId,
    stateKey = "feedback",
    onBack = navController::popBackStack,
)

BuildThisPleaseCreateRequest(
    client = client,
    stateKey = "feedback",
    onBack = navController::popBackStack,
    onCreated = navController::popBackStack,
)
```

Use a stable, unique `stateKey` for each feedback project. The SDK retains loaded state across configuration changes and restores the selected section and request after process recreation.

Create one long-lived client per project. Update that retained client when optional subscription or support identity changes. The request form also allows a user to provide an optional support email; the SDK normalizes it and sends it only when entered.

The `btp_pk_…` project key is publishable identification, not an administrator secret. Do not place a Google service-account key, dashboard token, or Cloudflare credential in an Android app.

## Localization

The Compose UI ships Android resources for the same 32 non-English locales as the iOS SDK: Arabic, Czech, Danish, Dutch, Filipino, Finnish, French, German, Greek, Hebrew, Croatian, Hungarian, Indonesian, Italian, Japanese, Korean, Malay, Norwegian Bokmål, Polish, Portuguese (Brazil and Portugal), Romanian, Russian, Slovak, Spanish, Swedish, Thai, Turkish, Ukrainian, Vietnamese, and Simplified and Traditional Chinese.

Android selects these resources from the device or app locale automatically. To expose the languages in Android 13+ per-app language settings, enable AGP's generated locale configuration in the consuming application module:

```kotlin
android {
    androidResources {
        generateLocaleConfig = true
    }
}
```

Also add `src/main/res/resources.properties` to that application module:

```properties
unqualifiedResLocale=en
```

The sample enables this configuration. The SDK does not change the host application's locale or require an in-app language picker. Shared translations can be refreshed with `python3 scripts/import_ios_localizations.py ../BuildThisPleaseSDK`; native-speaker review should remain part of release QA.

## Google Play setup

Production apps must be recognized by Google Play Integrity. For each distinct Play package:

1. Link the Play Console app to the Google Cloud project used for Play Integrity.
2. Enable the Play Integrity API and grant the backend service account permission to decode tokens.
3. Register the package name, Play App Signing SHA-256 certificate, Cloud project number, and environment in BuildThisPlease.
4. Upload the build to a Play testing or production track. A locally sideloaded production APK is not `PLAY_RECOGNIZED` and normally is not `LICENSED`.

The Cloud project number is returned by the backend challenge. A consuming app does not embed a service-account credential and does not need custom token-verification code.

## Trying the sample

Set `sdk.dir` in `local.properties`, then run:

```shell
./gradlew :sample:assembleDebug
```

The sample starts in mock mode and includes developer controls for mock scenarios, subscription state, appearance, data reset, and staging/production configuration. Supply optional local Gradle properties to exercise a Play-installed staging or production build:

```properties
BTP_STAGING_BASE_URL=https://staging.example.com
BTP_STAGING_PROJECT_KEY=btp_pk_...
BTP_PRODUCTION_BASE_URL=https://feedback.example.com
BTP_PRODUCTION_PROJECT_KEY=btp_pk_...
```

These publishable project keys may be local build configuration; Play Integrity service-account and signing credentials must never be placed here. Mock mode proves integration and UI without weakening production integrity requirements.

The mock supports normal, empty, loading, offline, rate-limited, expired-session, and server-error scenarios for previews and automated tests.

## Privacy and Play Data Safety

The SDK does not request contacts, advertising identifiers, location, or media permissions. It sends the app package/signing identity, app and OS versions, an installation identifier, feature requests, votes, and conversation messages. A host may additionally provide subscription state, RevenueCat App User ID, or support email. Declare the data your app actually supplies in Google Play Data Safety and in the host app's privacy policy. Do not provide optional identity data without an appropriate user-facing purpose and legal basis.

## Distribution

The SDK is released under the MIT License. Tagged releases are published to Maven Central as `io.github.rosday:buildthisplease-core` and `io.github.rosday:buildthisplease-compose`. The local composite-build integration remains available for SDK development.

See [SECURITY.md](SECURITY.md) for the trust model and operational cautions.
