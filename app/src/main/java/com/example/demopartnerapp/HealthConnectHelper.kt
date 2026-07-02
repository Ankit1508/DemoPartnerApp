package com.example.demopartnerapp

import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.demopartnerapp.bridge.JavaScriptCodeBuilder
import com.example.demopartnerapp.bridge.JavaScriptEvaluator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Connect-wearable support via Health Connect (steps + calories).
 *
 * Mirrors the ekincare app's HealthConnectHelper, trimmed to what a partner demo
 * needs: check availability, request read permissions, read today's totals, and
 * push a `syncSteps` event to the PWA. The permission LAUNCHER lives in the
 * hosting activity (must be registered before RESUMED); this class owns the
 * permission set, the reads and the JS callbacks.
 */
class HealthConnectHelper(
    private val context: Context,
    private val webView: WebView,
) {
    /** Read permissions the demo requests — steps + calories. */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    /** The ActivityResultContract the host activity registers a launcher with. */
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun providerUpdateRequired(): Boolean =
        HealthConnectClient.getSdkStatus(context) ==
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    private fun client() = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        isAvailable() && client().permissionController.getGrantedPermissions().containsAll(permissions)

    /** Read today's steps + calories and push a `syncSteps` event to the PWA. */
    suspend fun syncSteps() {
        if (!hasAllPermissions()) return
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val resp = client().aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, Instant.now()),
            ),
        )
        val steps = (resp[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
        val calories = resp[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toInt() ?: 0
        val lastSynced = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault()).format(LocalTime.now())
        val device = "${Build.BRAND} ${Build.MODEL}"
        JavaScriptEvaluator.sendToPwa(
            webView, JavaScriptCodeBuilder.syncSteps(steps, calories, lastSynced, device),
        )
    }

    /** Tell the PWA the wearable is connected (native-bridge flips its localStorage flag). */
    fun notifyConnected() =
        JavaScriptEvaluator.sendToPwa(webView, JavaScriptCodeBuilder.connectHealthConnect())

    fun notifyDisconnected() =
        JavaScriptEvaluator.sendToPwa(webView, JavaScriptCodeBuilder.disconnectHealthConnect())

    suspend fun revoke() {
        if (isAvailable()) client().permissionController.revokeAllPermissions()
    }
}
