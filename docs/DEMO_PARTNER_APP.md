# DemoPartnerApp

Reference Android app for the two ways a partner integrates with ekincare. Read this before changing the SSO flow.

- **PWA SSO** — log a user straight into the ekincare PWA, skipping login, landing on a deeplink.
- **Partner REST API** — server-to-server token auth + authenticated calls.

Main screen = two CTAs → `PwaSsoActivity` / `PartnerApiActivity`.

## Layout

```
com/example/demopartnerapp/
  MainActivity.kt        # 2 CTAs
  PwaSsoActivity.kt      # PWA SSO: payload -> AES-GCM -> WebView /pwa-login
  PartnerApiActivity.kt  # get-access-token (Basic) -> Bearer sample call
  AesGcm.kt              # AES-256-GCM, backend-compatible
local.properties(.sample)  # config -> BuildConfig (real file gitignored)
```

`compileSdk 36`, `minSdk 24`, AGP 9, Kotlin, ViewBinding. Dep: `okhttp`.

## Config

`local.properties` → `BuildConfig` (also editable at runtime):

```
EKIN_PWA_HOST=https://app-test.ekincare.com   # serves the /pwa-login SPA
EKIN_API_BASE=https://staging.ekincare.com    # Partner REST API base
EKIN_PARTNER_SLUG / EKIN_ENCODED_KEY / EKIN_ENCODED_IV   # per IntegrationPartner
EKIN_ENTITY_ID                                # company id
EKIN_API_USERNAME / EKIN_API_PASSWORD
```

## PWA SSO flow

1. Build payload: `entity_id, email, first_name, last_name, requested_at` (+ optional `gender, mobile, member_id, dob, deeplink`). `requested_at` = epoch secs; backend enforces a 30s replay window. Mandatory unique keys: **`entity_id` + `email`**.
2. AES-256-GCM encrypt (`AesGcm.kt`) → `message` + `auth_tag` (URL-safe base64, `ciphertext||16-byte tag`), matching backend `AesEncryption.gcm_decrypt`.
3. Load `<host>/pwa-login?slug=…&message=…&auth_tag=…` in a plain WebView.
4. The PWA POSTs `/v1/customers/pwa-sso/:slug`, persists the session, redirects to the deeplink.

## Non-obvious fixes — don't regress

**1. APP-VERSION must be seeded (the reason SSO landed on /login).**
The PWA sends an `app-version` header on every data call = `localStorage['APP-VERSION']`. A plain WebView never seeds it, so it's below the backend minimum (`UNSUPPORTED_APP_VERSION = '23.7.3'`). Backend returns `401 "Please update your app"`; the PWA treats any 401 as session-expiry → wipes auth → `/login`. Fix:
```kotlin
const val APP_VERSION_SHIM =
  "(function(){ try{ window.localStorage.setItem('APP-VERSION','99.9.9'); }catch(e){} })();"
// injected in onPageStarted + onPageFinished
```

**2. Build the query string manually.**
`Uri.appendQueryParameter` percent-encodes the base64 `=` padding to `%3D`; the PWA forwards it undecoded → backend `urlsafe_decode64` fails ("Unable to process the login request"). Use `"$host/pwa-login?slug=$slug&message=$message&auth_tag=$authTag"`.

**3. Keep the WebView plain — no JS bridge.**
Injecting `ekincareAndroidInterface` flips the PWA to native-app mode, where it expects native to own the session and ignores the web SSO session → login.

**4. Same backend for SSO + data.**
SSO and data APIs must hit one environment. `app-test` serves the SPA but its build calls `staging` for data — both must be the same backend or the session isn't shared.

**5. Geolocation** granted via `WebChromeClient.onGeolocationPermissionsShowPrompt` + runtime permission (plain WebView denies it by default).

## Partner REST API flow

1. `GET /api/get-access-token` (HTTP Basic) → `{ access_token, expires_at }` (1h).
2. Call endpoints with `Authorization: Bearer <token>`. Requires `company.running_contract.product_type == "api"`.

## Build / run

```bash
./gradlew :app:installDebug
adb shell monkey -p com.example.demopartnerapp -c android.intent.category.LAUNCHER 1
```

Verify SSO: Proceed with PWA → Launch → lands on the deeplink page, not login.
Changing `local.properties` needs `--no-configuration-cache` (or clean) to refresh BuildConfig.

## Where to change

- **SSO payload field** → `PwaSsoActivity.launch()` + `activity_pwa_sso.xml` + `prefill()`
- **Target env** → `local.properties` (keep SSO + data on one backend)
- **App version sent** → `APP_VERSION_SHIM`
- **Partner API call** → `PartnerApiActivity`
- **Native-app-mode bridge** (removed) → re-add a WebView host injecting `ekincareAndroidInterface`
