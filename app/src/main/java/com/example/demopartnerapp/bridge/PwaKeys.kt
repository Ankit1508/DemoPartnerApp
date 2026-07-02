package com.example.demopartnerapp.bridge

/**
 * Bridge constants shared by the JS <-> native layer.
 * Mirrors EkincarePwa's PwaKeys — the injected global name is the contract the
 * PWA checks (`typeof window.ekincareAndroidInterface !== 'undefined'`), so it
 * MUST stay exactly this string.
 */
object PwaKeys {
    /** WebView.addJavascriptInterface(...) name. PWA: window.ekincareAndroidInterface */
    const val JS_INTERFACE = "ekincareAndroidInterface"

    /** Native -> JS entry point the PWA exposes (index.html / native-bridge.js). */
    const val JS_RECEIVER = "handleNativeMessage"

    /** Version handed to the PWA via localStorage['APP-VERSION']; must be >= backend minimum (23.7.3). */
    const val APP_VERSION = "99.9.9"

    // localStorage keys native seeds (see BridgeLocalStorage) — parity with getLocalStorageDeviceId.
    const val LS_DEVICE_ID = "X-DEVICE-ID"
    const val LS_PUSH_TOKEN = "PUSH-TOKEN"
    const val LS_DEVICE_NAME = "DEVICE_NAME"
    const val LS_DEVICE_TYPE = "DEVICE_TYPE"
    const val LS_TOKEN_TYPE = "TOKEN_TYPE"
    const val LS_APP_VERSION = "APP-VERSION"
    const val LS_MFA_ENABLED = "MFAEnabled"
    const val LS_TARGET_NAME = "TARGET-NAME"
    const val LS_HC_CONNECTED = "nativeWearableHealthConnectConnected"
}
