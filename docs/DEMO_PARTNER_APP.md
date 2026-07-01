# DemoPartnerApp

Reference Android app for the two ways a partner integrates with ekincare. Read this before changing the SSO flow.

- **PWA SSO** — log a user straight into the ekincare PWA, skipping login, landing on a deeplink.
- **Partner REST API** — server-to-server token auth + authenticated calls.

Main screen = two CTAs → `PwaSsoActivity` / `PartnerApiActivity`.

## Layout

```
com/example/demopartnerapp/
  MainActivity.kt        # 2 CTAs
  PwaSsoActivity.kt      # PWA SSO: payload -> AES-GCM -> WebView /pwa-login (bridge injected)
  PartnerApiActivity.kt  # get-access-token (Basic) -> Bearer sample call
  AesGcm.kt              # AES-256-GCM, backend-compatible
  bridge/                # JS<->native layer, mirrors EkincarePwa
    PwaKeys.kt                 # constants (ekincareAndroidInterface, APP-VERSION, LS keys)
    JavaScriptInterfaceee.kt   # injected object: postMessage / getBase64FromBlobData
    HandleHeaderFromScript.kt  # parse {action, payload}
    WebViewMethodHandler.kt    # the `when` dispatch (the protocol)
    JavaScriptCodeBuilder.kt   # native->JS envelopes
    JavaScriptEvaluator.kt     # main-thread evaluateJavascript / handleNativeMessage
    BridgeLocalStorage.kt      # getLocalStorageDeviceId parity (seeds APP-VERSION etc.)
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
3. Load `<host>/pwa-login?slug=…&message=…&auth_tag=…` in a WebView with the `ekincareAndroidInterface` bridge injected.
4. The PWA POSTs `/v1/customers/pwa-sso/:slug`, persists the session, calls `saveHeaders`/`saveCustomer` over the bridge, redirects to the deeplink.

## Non-obvious fixes — don't regress

**1. APP-VERSION must be seeded (the reason SSO landed on /login).**
The PWA sends an `app-version` header on every data call = `localStorage['APP-VERSION']`. If unseeded it's below the backend minimum (`UNSUPPORTED_APP_VERSION = '23.7.3'`). Backend returns `401 "Please update your app"`; the PWA treats any 401 as session-expiry → wipes auth → `/login`. Seeded (with `X-DEVICE-ID`, `DEVICE_NAME`, … ) in `BridgeLocalStorage.getLocalStorageDeviceId`, injected in `onPageStarted` + `onPageFinished`. **This is the one load-bearing seed.**

**2. Build the query string manually.**
`Uri.appendQueryParameter` percent-encodes the base64 `=` padding to `%3D`; the PWA forwards it undecoded → backend `urlsafe_decode64` fails ("Unable to process the login request"). Use `"$host/pwa-login?slug=$slug&message=$message&auth_tag=$authTag"`.

**3. The `ekincareAndroidInterface` bridge is SAFE — it does NOT break SSO.**
Verified against the PWA source: SSO session persistence (`PwaSSOLoginPage.js:213-214`) has no `isEkincareApp()` branch. The SSO page *itself* calls the bridge — `saveHeadersToMobile`/`saveCustomerToMobile` → `postMessage({action:"saveHeaders"/"saveCustomer"})` — to EXPORT the already-established web session to native (they fire *after* web auth succeeds, not before). So injecting the bridge is expected and the PWA still lands on the deeplink. *(An earlier revision of this doc wrongly said the bridge flips the PWA to native-owned auth → login; the real bounce was always fix #1.)*

**4. Same backend for SSO + data.**
SSO and data APIs must hit one environment. `app-test` serves the SPA but its build calls `staging` for data — both must be the same backend or the session isn't shared.

**5. Geolocation** granted via `WebChromeClient.onGeolocationPermissionsShowPrompt` + runtime permission (WebView denies it by default). Other permissions (camera/mic/location/notification/storage) are requested when the PWA calls `requestPermissions` over the bridge.

## Bridge protocol (`bridge/`)

Mirrors EkincarePwa. PWA→native: `ekincareAndroidInterface.postMessage(JSON.stringify({action, payload}))` → `HandleHeaderFromScript` → `WebViewMethodHandler` `when`. Native→PWA: `window.handleNativeMessage({action, payload})` via `JavaScriptEvaluator`.

Handled for real: `close`, `fetchPermissionsData`, `requestPermissions`/`requestAppPermissions`, `fetchLocation`, `externalurl`, `sharelink`/`nativeShare`, `setStatusBarColor`, `trackEvent`, `saveHeaders`/`saveCustomer`, `dashboardPageLoad`, blob download (`getBase64FromBlobData`). Acknowledged (Toast + expected callback) but not implemented — no SDK in the demo: `collectPayment`, `joinStreamCall`/`endStreamCall`, `connect*/syncSteps` (health), `enableMFA`/`disableMFA`, `open_qr_scanner`, `playStoreRating`, `open-support`.

To add a real handler: add a `when` case in `WebViewMethodHandler`, and (if it needs a runtime permission / launcher) route through the `BridgeHost` methods on `PwaSsoActivity`.

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
- **App version / seeded localStorage** → `BridgeLocalStorage.getLocalStorageDeviceId`
- **Partner API call** → `PartnerApiActivity`
- **A bridge action (PWA→native)** → `WebViewMethodHandler` `when` (+ `BridgeHost` on `PwaSsoActivity` for permission/launcher work)
- **A native→PWA callback** → `JavaScriptCodeBuilder` + `JavaScriptEvaluator.sendToPwa`
