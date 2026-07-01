# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

`DemoPartnerApp` — a minimal Android reference app showing the two ways a partner integrates with **ekincare**:

1. **PWA SSO** (`PwaSsoActivity`) — encrypt a partner payload → load the ekincare PWA `/pwa-login` in a WebView → the PWA authenticates and lands on a deeplink (login screen skipped).
2. **Partner REST API** (`PartnerApiActivity`) — HTTP Basic → `get-access-token` → Bearer sample call.

`MainActivity` is just two CTAs routing to these. There is no shared business logic — each activity is self-contained.

- `applicationId` / namespace: `com.example.demopartnerapp`
- `compileSdk 36`, `minSdk 24`, AGP 9, Kotlin, ViewBinding + BuildConfig. Only extra dep: `okhttp`.
- No product flavors — variants are just `debug` / `release`.

Full reference & change guide: **`docs/DEMO_PARTNER_APP.md`** (read it before touching the SSO flow).

## Commands

```bash
./gradlew :app:assembleDebug          # build
./gradlew :app:installDebug           # install on device/emulator
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:testDebugUnitTest --tests "com.example.demopartnerapp.ExampleUnitTest"   # single test
./gradlew :app:connectedDebugAndroidTest   # instrumented tests
./gradlew :app:lintDebug              # lint
adb shell monkey -p com.example.demopartnerapp -c android.intent.category.LAUNCHER 1   # launch
```

**Config-cache gotcha:** `local.properties` is read at configuration time into `BuildConfig`. After editing it, build with `--no-configuration-cache` (or `clean`) or the new values won't take effect.

## Configuration

`local.properties` (gitignored; template in `local.properties.sample`) → `BuildConfig`, also editable at runtime in the forms:

```
EKIN_PWA_HOST      # host serving the /pwa-login SPA (e.g. app-test.ekincare.com)
EKIN_API_BASE      # Partner REST API base (e.g. staging.ekincare.com)
EKIN_PARTNER_SLUG / EKIN_ENCODED_KEY / EKIN_ENCODED_IV   # per ekincare IntegrationPartner
EKIN_ENTITY_ID     # company id
EKIN_API_USERNAME / EKIN_API_PASSWORD
```

## Architecture that isn't obvious from the files

The PWA SSO flow (`PwaSsoActivity`) depends on several ekincare backend/PWA behaviors. These fixes are load-bearing — do not regress:

- **`APP-VERSION` must be seeded in the WebView** (`APP_VERSION_SHIM`, injected on page start/finish). The PWA sends an `app-version` header on every data call from `localStorage['APP-VERSION']`; the backend rejects versions below its minimum (`23.7.3`) with `401 "Please update your app"`, which the PWA treats as session-expiry → wipes auth → redirects to `/login`. Without seeding, SSO succeeds but bounces to login.
- **Query string is built manually**, not via `Uri.appendQueryParameter` — that percent-encodes the base64 `=` padding to `%3D`, which the PWA forwards undecoded and the backend fails to `urlsafe_decode64`.
- **The WebView stays plain (no `ekincareAndroidInterface` JS bridge).** Injecting it flips the PWA into native-app mode, where it expects native to own the session and ignores the web SSO session → login.
- **SSO and data APIs must hit the same backend.** The SPA host and its configured data-API base must be one environment or the session isn't shared.

`AesGcm.kt` output must stay byte-compatible with the backend `AesEncryption.gcm_decrypt`: AES-256-GCM, key/iv base64-decoded, output split into `ciphertext || 16-byte tag`, both URL-safe base64.

## Workflow

Repo: `Ankit1508/DemoPartnerApp`. Changes go via feature branch → PR → merge (do not push to `main` directly).
