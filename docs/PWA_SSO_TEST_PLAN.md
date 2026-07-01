# PWA SSO — Test Plan (Model B)

Covers `PwaSsoActivity` → AES-256-GCM payload → `{host}/pwa-login` WebView → PWA self-login → deeplink redirect.

## 1. Scope

| In scope | Out of scope |
|---|---|
| PWA SSO launch flow (`PwaSsoActivity`) | Partner REST API (`PartnerApiActivity`, Model A) |
| Payload build, AES-256-GCM encryption (`AesGcm.encrypt`) | Backend `/v1/customers/pwa-sso/:slug` internal logic |
| URL assembly, WebView load, session handoff, deeplink redirect | Native push, deep OS integration |
| Input validation, replay window, back navigation | App store / release packaging |

## 2. Environment

- **Host:** `https://staging.ekincare.com` (`EKIN_PWA_HOST`)
- **Device:** Android emulator API 30+ and one physical device. WebView (Chrome) up to date.
- **Network:** device clock synced (NTP). `requested_at` must be within **30s** of server clock — clock skew = guaranteed failure.
- **Build:** debug build with `local.properties` filled (slug/key/iv/entity_id non-empty).

## 3. Sample Test Data

Base set (fill the empty `local.properties` keys with staging partner values before running):

```
EKIN_PWA_HOST   = https://staging.ekincare.com
EKIN_PARTNER_SLUG = <staging partner slug>
EKIN_ENCODED_KEY  = <base64 AES-256 key>   # decodes to 32 bytes
EKIN_ENCODED_IV   = <base64 GCM iv>        # decodes to 12 bytes
EKIN_ENTITY_ID    = <staging entity id>
```

Payload fields (form, prefilled values shown):

| Field | Sample value |
|---|---|
| entity_id | `<EKIN_ENTITY_ID>` |
| email | `qa.pwa.user@example.com` |
| first_name | `Asha` |
| last_name | `Verma` |
| gender | `Male` (prefilled) |
| mobile | `9000000001` |
| member_id | `MBR-TEST-001` |
| dob | `1990-01-15` |
| deeplink | `benefits` (prefilled) |
| requested_at | auto (epoch seconds, set at launch) |

## 4. Test Cases

### TC-01 Happy path — full payload
1. Open app → tap **PWA SSO**.
2. Confirm prefill: host/slug/key/iv/entity_id from BuildConfig; gender=`Male`; deeplink=`benefits`.
3. Fill remaining fields from sample set.
4. Tap **Launch**.
- **Expect:** debug text shows `payload=…` + assembled `URL=…`. WebView opens, PWA `/pwa-login` self-posts, session stored, redirect to `benefits` deeplink. User lands authenticated.

### TC-02 Minimal required fields
- Clear email/first/last/mobile/member_id/dob; keep host/slug/key/iv (+ entity_id).
- Tap Launch.
- **Expect:** passes the client guard (only host/slug/key/iv required at `launch()`). Backend acceptance depends on partner config — record whether `/pwa-sso/:slug` accepts sparse payload.

### TC-03 Missing required field — client guard
- Blank **slug** (also test blank host / key / iv individually).
- **Expect:** toast `host, slug, key and iv are required`. No WebView, no network call.

### TC-04 Bad key/iv — encrypt failure
- Set `EKIN_ENCODED_KEY` to non-base64 / wrong length (e.g. 16-byte key).
- **Expect:** toast `Encrypt failed: <reason>`. No URL built, no WebView.

### TC-05 Replay window — stale requested_at
- Launch, then delay session post >30s (throttle network / pause).
- **Expect:** backend rejects (replay protection). PWA surfaces error, no session.

### TC-06 URL correctness
- Inspect debug `URL=` line.
- **Expect:** `https://staging.ekincare.com/pwa-login?slug=<slug>&message=<urlsafe-b64>&auth_tag=<urlsafe-b64>`. `message`/`auth_tag` URL-safe base64, properly query-encoded, no wrap.

### TC-07 Crypto parity with backend
- Encrypt a known payload with `AesGcm.encrypt` and same key/iv via backend Ruby `AesEncryption.gcm_encrypt`.
- **Expect:** identical `message` + `auth_tag` (16-byte tag split off ciphertext). Covered by unit test below.

### TC-08 Host trailing slash
- Set host `https://staging.ekincare.com/`.
- **Expect:** `trimEnd('/')` → no `//pwa-login`. URL clean.

### TC-09 Deeplink variants
- Try `benefits`, empty, and an unknown deeplink.
- **Expect:** valid → lands on target; empty → PWA default landing; unknown → PWA fallback/error (record behavior).

### TC-10 WebView capability
- Confirm during PWA login: JS enabled, `localStorage`/`sessionStorage` work (domStorage), DB enabled — session persists across in-WebView nav.

### TC-11 Back navigation
- In WebView with history → press Back → goes back in WebView.
- At WebView root / form visible → Back exits activity.

### TC-12 Special chars in payload
- `first_name=O'Brien`, unicode name, `+` in mobile.
- **Expect:** JSON-encoded correctly, encrypts, decrypts server-side intact (no query-param breakage since data rides inside encrypted `message`).

## 5. Unit / Instrumented Coverage

- **Unit (`test/`):** `AesGcm.encrypt` parity — fixed key/iv/plaintext → assert known `message`/`auth_tag`; assert tag is last 16 bytes; assert URL-safe + no-wrap base64. Replace placeholder `ExampleUnitTest`.
- **Instrumented (`androidTest/`):** launch `PwaSsoActivity`, assert prefill values, assert empty-required-field toast, assert WebView becomes visible after valid Launch (Espresso). Replace placeholder `ExampleInstrumentedTest`.

## 6. Pass / Fail

- **Pass:** TC-01, 03, 04, 06, 07, 08, 11 deterministic pass; happy path lands authenticated on deeplink.
- **Fail/Block:** any crypto-parity mismatch (TC-07), URL malformation (TC-06), or replay window not enforced (TC-05).

## 7. Risks / Notes

- `local.properties` ships empty — tests need real staging slug/key/iv first.
- 30s replay window makes clock skew the top flaky-failure cause. Sync clock before runs.
- Secrets live in `BuildConfig` (debug only). Do not commit filled `local.properties`.
