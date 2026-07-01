# DemoPartnerApp — Reference & Change Guide

Living document for the ekincare **DemoPartnerApp**. Use it to understand the app and to plan/implement further changes.

---

## 1. Purpose

A minimal Android app that demonstrates the **two ways a partner integrates with ekincare**:

| Flow | What it shows | Who calls |
|---|---|---|
| **PWA SSO** (Model B) | Drop a user straight into the ekincare PWA — skip the login screen, land on a deeplink | User's device / partner app → PWA |
| **Partner REST API** (Model A) | Server-to-server auth + a sample authenticated call | Partner backend → ekincare API |

Main screen = two CTAs: **Proceed with PWA** / **Proceed with Partner API**.

---

## 2. Project layout

```
app/src/main/java/com/example/demopartnerapp/
  MainActivity.kt          # 2 CTAs -> PwaSsoActivity / PartnerApiActivity
  PwaSsoActivity.kt        # Model B: PWA SSO (payload -> encrypt -> WebView /pwa-login)
  PartnerApiActivity.kt    # Model A: get-access-token (Basic) -> Bearer sample call
  AesGcm.kt                # AES-256-GCM encrypt, backend-compatible
app/src/main/res/layout/
  activity_main.xml
  activity_pwa_sso.xml     # form + WebView
  activity_partner_api.xml
local.properties(.sample)  # config -> BuildConfig (real file gitignored)
```

- `compileSdk 36`, `minSdk 24`, AGP 9, Kotlin, ViewBinding + BuildConfig.
- Deps: `okhttp` (Partner API). No Gson (removed). No paid SDKs.

---

## 3. Config (`local.properties` → BuildConfig)

Read at build time into `BuildConfig`; also editable at runtime in the forms.

```
EKIN_PWA_HOST=https://app-test.ekincare.com   # host serving the PWA /pwa-login SPA
EKIN_API_BASE=https://staging.ekincare.com    # Partner REST API base
EKIN_PARTNER_SLUG=<integration_partner slug>
EKIN_ENCODED_KEY=<base64 AES-256 key>         # per IntegrationPartner
EKIN_ENCODED_IV=<base64 AES iv>               # per IntegrationPartner
EKIN_ENTITY_ID=<company id>
EKIN_API_USERNAME=<partner api username>
EKIN_API_PASSWORD=<partner api password>
```

`local.properties` is gitignored — never commit real keys. `local.properties.sample` is the template.

---

## 4. Model B — PWA SSO (the main flow)

### Client steps (`PwaSsoActivity`)
1. Build payload JSON:
   `entity_id, email, first_name, last_name, gender, mobile, member_id, dob, deeplink, requested_at`
   (`requested_at` = epoch seconds; backend enforces a **30-second** replay window.)
2. **AES-256-GCM encrypt** (`AesGcm.kt`): key/iv base64-decoded; output split into `ciphertext || 16-byte tag`; both URL-safe base64. Returns `(message, auth_tag)` — byte-compatible with backend `AesEncryption.gcm_decrypt`.
3. Load `<host>/pwa-login?slug=…&message=…&auth_tag=…` in a **plain WebView**.
4. The PWA page (`PwaSSOLoginPage`) POSTs to `/v1/customers/pwa-sso/:slug`, persists the session in `localStorage`, and navigates to the deeplink.

### Backend (wellness) contract
- `v1/customers/pwa_sessions_controller#pwa_session` → `AuthenticatePwaCustomer`:
  - `gcm_decrypt(message, partner, auth_tag)` → JSON
  - `requested_at` within 30s else `request_expired`
  - find company by `entity_id`, find/create customer by `email`
  - response headers `X-EKINCARE-KEY` (access-token secure_token), `X-CUSTOMER-KEY` (identification_token); also sets auth cookies; returns `deeplink_url`.
- Token created: `access_tokens.find_or_initialize_by(channel_id: params[:channel_id] || 'pwa-web')`.

### Minimum payload fields
Mandatory/unique: **`entity_id` + `email`** + `requested_at` + `first_name`/`last_name`.
Optional: `gender, mobile, member_id, dob, grade, department, deeplink, voluntary_plan_ids[]`.

---

## 5. Critical gotchas / fixes baked in (READ before changing PwaSsoActivity)

These were found the hard way — don't regress them.

### 5.1 URL query must be built manually
```kotlin
val url = "$host/pwa-login?slug=$slug&message=$message&auth_tag=$authTag"
```
`Uri.Builder.appendQueryParameter` percent-encodes the base64 `=` padding to `%3D`. The PWA's `queryParams()` forwards the value **without** decoding → backend `Base64.urlsafe_decode64` fails → **"Unable to process the login request"**. A literal `=` in a query value round-trips fine.

### 5.2 Seed `APP-VERSION` (else SSO succeeds but bounces to /login) ⭐ ROOT CAUSE
```kotlin
const val APP_VERSION_SHIM =
  "(function(){ try { window.localStorage.setItem('APP-VERSION','99.9.9'); } catch(e){} })();"
// injected in onPageStarted + onPageFinished
```
Why:
- The `app-test` PWA build has `VITE_APP_BASE_URL` set, so `request.js` sends an `app-version` header on **every** data call = `localStorage['APP-VERSION']`.
- A plain WebView never seeds it (only the native app does, via `getLocalStorageDeviceId`) → version below the backend minimum.
- Backend `base_mobile_api_controller`: `UNSUPPORTED_APP_VERSION = '23.7.3'`; `check_old_app_version` returns **401 "Please update your app"** for lower versions.
- The PWA's `errorDecorator` treats any **401 as session-expiry** → clears `EKINCARE_KEY`/`CUSTOMER_KEY` → redirect `/login`.

Seeding a valid version keeps the SSO session alive → app lands on the deeplink.
`99.9.9` clears every min-version gate; swap for the real ekincare app version if you want realism.

### 5.3 Auth model (for reference)
Data endpoints (`CustomerAppController#check_customer_signed_in` → `TokenValidationHelper.validate_tokens`) require `X-EKINCARE-KEY` + `X-CUSTOMER-KEY` + `X-DEVICE-ID`, and match an `access_token` where `channel_id == x-device-id` and `secure_token == x-ekincare-key`. `x-device-id = window.channelId()` (web mode = `navigator.userAgent`, constant → consistent with the SSO token). This part was **not** the bug — the app-version gate was.

### 5.4 Plain WebView on purpose (no JS bridge)
The SSO WebView does **not** inject `ekincareAndroidInterface`. If it did, the PWA detects native-app mode (`isAndroidWebView()`), expects native to own the session, and ignores the web SSO session → falls back to login. Keep it plain.

### 5.5 HTML5 geolocation
Plain WebView denies `navigator.geolocation` by default. Granted via `WebChromeClient.onGeolocationPermissionsShowPrompt` + a runtime location permission launcher.

### 5.6 Host consistency (env note)
SSO and data APIs must hit the **same backend**. `app-test.ekincare.com` serves the SPA; its build points data calls at `staging.ekincare.com`. Both must be one environment or the session won't be shared. If you switch `EKIN_PWA_HOST`, make sure that host's API base is the same backend where SSO authenticates.

---

## 6. Model A — Partner REST API (`PartnerApiActivity`)

1. `GET /api/get-access-token` with **HTTP Basic** (username/password issued at partner registration) → `{ access_token, expires_at }` (1h TTL).
2. Any endpoint with `Authorization: Bearer <access_token>`. Demo does an editable sample GET (default `/api/providers`).
3. Backend gate: `company.running_contract.product_type == "api"`.

Other Model A endpoints (from the Postman docs): appointments (providers/slots/create/reschedule/cancel/voucher/reports), consultations (inclinic/specialist/24x7), prescriptions digitize, epharmacy, partner customers, pre-registration.

---

## 7. Build / run / test

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
# launch
adb shell monkey -p com.example.demopartnerapp -c android.intent.category.LAUNCHER 1
```

Manual verify (SSO): open app → Proceed with PWA → fill/confirm creds → Launch → should land on the deeplink page (e.g. "Your Health Benefits"), NOT the login screen.

Config-cache note: changing `local.properties` needs `--no-configuration-cache` (or a clean) for new BuildConfig values to take effect.

---

## 8. Deliberately NOT included

Removed/never added to keep the demo self-contained:
- The full JS↔native bridge (`ekincareAndroidInterface`, `WebViewMethodHandler`, permission/download/chrome shim) and native-app-mode host — were prototyped, then removed.
- Paid SDKs: Stream video, Razorpay, CometChat, MoEngage, Health Connect, Freshchat, MFA.

If a future task needs the bridge (native-app-mode against the live PWA), it can be re-added — the PWA detects it via `typeof window.ekincareAndroidInterface !== 'undefined'`.

---

## 9. Extending — where to touch

| Want to… | Do this |
|---|---|
| Add a payload field to SSO | `PwaSsoActivity.launch()` payload + `activity_pwa_sso.xml` field + `prefill()` |
| Change target env | `local.properties` (`EKIN_PWA_HOST` / `EKIN_API_BASE`) — keep SSO+data on one backend |
| Add a Partner API call | `PartnerApiActivity` (Bearer sample); or add a new action |
| Tune the app version sent | `APP_VERSION_SHIM` in `PwaSsoActivity` |
| Add Pre-registration API demo | New activity + OkHttp; `get access-token` (Basic) → `POST /v1/corporate/pre_registrations` with `X-EKINCARE-KEY` |
| Re-add native bridge | New WebView host that injects `ekincareAndroidInterface` + method dispatch |

---

## 10. Repo

- GitHub: `Ankit1508/DemoPartnerApp` (branch `main`).
- This doc lives on `docs/demo-partner-app` → merge via PR.
