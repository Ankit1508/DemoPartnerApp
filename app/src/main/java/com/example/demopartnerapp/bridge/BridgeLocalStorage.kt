package com.example.demopartnerapp.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Builds the localStorage-seeding JS injected before the PWA runs.
 * Mirrors EkincarePwa's WebUtils.getLocalStorageDeviceId(...) — the PWA reads
 * these keys for device identity, app-version gating and feature flags.
 *
 * APP-VERSION is the load-bearing one: the PWA sends it as the `app-version`
 * header on every data call; below the backend minimum (23.7.3) the backend
 * returns 401 "Please update your app", which the PWA treats as session-expiry
 * and bounces to /login. Seeding a valid version keeps the SSO session alive.
 */
object BridgeLocalStorage {

    @SuppressLint("HardwareIds")
    fun getLocalStorageDeviceId(
        context: Context,
        pushToken: String = "",
        mfaEnabled: Boolean = false,
        healthConnectConnected: Boolean = false,
    ): String {
        val deviceId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID,
        ) ?: "demo-partner-device"
        val deviceName = "${Build.BRAND} ${Build.MODEL}"

        fun set(key: String, value: String) =
            "try { window.localStorage.setItem('$key', '$value'); } catch(e){}"

        return "(function(){" +
            set(PwaKeys.LS_DEVICE_ID, deviceId) +
            set(PwaKeys.LS_PUSH_TOKEN, pushToken) +
            set(PwaKeys.LS_DEVICE_NAME, deviceName) +
            set(PwaKeys.LS_DEVICE_TYPE, "android") +
            set(PwaKeys.LS_TOKEN_TYPE, "fcm") +
            set(PwaKeys.LS_APP_VERSION, PwaKeys.APP_VERSION) +
            set(PwaKeys.LS_MFA_ENABLED, mfaEnabled.toString()) +
            set(PwaKeys.LS_HC_CONNECTED, healthConnectConnected.toString()) +
            set(PwaKeys.LS_TARGET_NAME, "ekincare") +
            "})();"
    }
}
