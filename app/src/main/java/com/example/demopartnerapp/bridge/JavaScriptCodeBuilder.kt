package com.example.demopartnerapp.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the {action, payload} envelopes native pushes back to the PWA via
 * window.handleNativeMessage(...). Action names match native-bridge.js exactly.
 * Mirrors EkincarePwa's JavaScriptCodeBuilder.
 */
object JavaScriptCodeBuilder {

    private fun envelope(action: String, payload: JSONObject = JSONObject()): String =
        JSONObject().put("action", action).put("payload", payload).toString()

    /** { action: receivePermissionsData, payload: { <name>: granted|denied|prompt, ... } } */
    fun permissionsData(statuses: Map<String, String>): String {
        val payload = JSONObject()
        statuses.forEach { (k, v) -> payload.put(k, v) }
        return envelope("receivePermissionsData", payload)
    }

    /** { action: receiveLocationData, payload: { status, coords:{latitude,longitude} } } */
    fun locationData(status: String, lat: Double?, lon: Double?): String {
        val payload = JSONObject().put("status", status)
        if (lat != null && lon != null) {
            payload.put("coords", JSONObject().put("latitude", lat).put("longitude", lon))
        }
        return envelope("receiveLocationData", payload)
    }

    fun mfaEnabled(): String = envelope("enableMFA")
    fun mfaDisabled(): String = envelope("disableMFA")

    fun paymentFailure(code: Int, message: String): String =
        envelope("paymentFailure", JSONObject().put("code", code).put("message", message))

    fun connectHealthConnect(): String = envelope("connectHealthConnectWearable")

    /** { action: syncSteps, payload: { todaySteps, todayCalories, steps:[...], source, last_synced_at, device_name } } */
    fun syncSteps(todaySteps: Int, todayCalories: Int, lastSyncedAt: String, deviceName: String): String {
        val steps = JSONArray().put(
            JSONObject().put("count", todaySteps).put("calories", todayCalories),
        )
        val payload = JSONObject()
            .put("todaySteps", todaySteps.toString())
            .put("todayCalories", todayCalories.toString())
            .put("steps", steps)
            .put("source", "android")
            .put("last_synced_at", lastSyncedAt)
            .put("device_name", deviceName)
        return envelope("syncSteps", payload)
    }

    fun disconnectHealthConnect(): String = envelope("disconnectHealthConnectWearable")
    fun disconnectNativeWearable(): String = envelope("disconnectNativeWearable")

    fun qrScanData(result: String): String =
        envelope("qrScanData", JSONObject().put("data", result))

    fun pushTokenUpdated(token: String): String =
        envelope("pushTokenUpdated", JSONObject().put("token", token))

    fun appResumed(): String = envelope("AppResumed")
}
