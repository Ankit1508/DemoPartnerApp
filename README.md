# DemoPartnerApp

A minimal Android reference app showing the two ways a partner integrates with **ekincare**:

1. **PWA SSO** — log a user straight into the ekincare PWA (skips the login screen, lands on a deeplink).
2. **Partner REST API** — server-to-server auth (`get-access-token`) + a sample authenticated call.

Single screen with two CTAs: **Proceed with PWA** / **Proceed with Partner API**.

## How it works

### PWA SSO (`PwaSsoActivity`)
- Builds the partner payload (`entity_id, email, first_name, …, requested_at`).
- AES-256-GCM encrypts it (`AesGcm.kt`) → `message` + `auth_tag` (URL-safe base64), byte-compatible with the backend `AesEncryption.gcm_decrypt`.
- Loads `<host>/pwa-login?slug&message&auth_tag` in a WebView with the `ekincareAndroidInterface` JS bridge injected (`bridge/`, mirrors the EkincarePwa app). The PWA posts to `/v1/customers/pwa-sso/:slug`, persists the session, exports it to native via the bridge (`saveHeaders`/`saveCustomer`), and redirects to the deeplink.

Notes baked in from real debugging:
- Query string is built manually — `Uri.appendQueryParameter` percent-encodes the base64 `=` padding to `%3D`, which the backend fails to decode.
- Seeds `APP-VERSION` (+ `X-DEVICE-ID`, device info) in `localStorage` before the PWA runs. The PWA sends an `app-version` header on every data call; the backend rejects versions below its minimum with `401 "Please update your app"`, which the PWA treats as session-expiry → bounces to login. Seeding a valid version keeps the SSO session alive.
- The bridge (`WebViewMethodHandler`) services the PWA's native calls — permissions, location, share, external URL, downloads, etc. It is safe to inject and does NOT break the web SSO session (the PWA's SSO flow expects it).
- HTML5 geolocation is granted via `WebChromeClient`.

### Partner REST API (`PartnerApiActivity`)
- `GET /api/get-access-token` with HTTP Basic → access token (1h).
- Sample call with `Authorization: Bearer <token>` (editable path).

## Setup

Copy `local.properties.sample` → `local.properties` and fill your staging values (this file is gitignored):

```
EKIN_PWA_HOST=https://<pwa-host>
EKIN_API_BASE=https://<api-host>
EKIN_PARTNER_SLUG=<integration_partner slug>
EKIN_ENCODED_KEY=<base64 AES key>
EKIN_ENCODED_IV=<base64 AES iv>
EKIN_ENTITY_ID=<company id>
EKIN_API_USERNAME=<partner api username>
EKIN_API_PASSWORD=<partner api password>
```

All fields are also editable at runtime in the app forms.

## Build / run

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

- `compileSdk 36`, `minSdk 24`, AGP 9, Kotlin.
