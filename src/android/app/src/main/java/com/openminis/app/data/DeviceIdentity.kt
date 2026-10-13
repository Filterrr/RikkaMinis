package com.openminis.app.data

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Stable per-install device identity. Mirrors iOS `DeviceIdentity` minus the
 * cross-end iCloud Keychain sync (Android has no equivalent; spec §4.4 calls
 * this out). The uuid survives app kill but not uninstall — that's the most
 * privacy-friendly stable id Android offers without relying on
 * `Settings.Secure.ANDROID_ID` (which some OEMs reset and which Play policy
 * restricts).
 *
 * Usage patterns across the app:
 *   - `zoneName` — namespace any future multi-device sync records.
 *   - `deviceName` — friendly "Pixel 8 · A3F7"-style label for debug logs
 *     and the provider-account UI (future).
 *   - `deviceId` — opaque UUID for any "my-device" disambiguation.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "minis_device_identity"
    private const val KEY_DEVICE_ID = "deviceId"

    @Volatile private var cached: String? = null

    fun deviceId(context: Context): String {
        cached?.let { return it }
        val prefs = encryptedPrefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        cached = fresh
        return fresh
    }

    /** "Pixel 8 Pro · A3F7" — last four chars of [deviceId] uppercased. */
    fun deviceName(context: Context): String {
        val shortId = deviceId(context).takeLast(4).uppercase()
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        val model = Build.MODEL
        val label = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return "$label · $shortId"
    }

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    /** Stable sync-zone namespace — mirrors iOS `"device-\(deviceId)"`. */
    fun zoneName(context: Context): String = "device-${deviceId(context)}"

    // ---- [backup-upstream-1.14] user-overridable device name ---------------
    // Upstream's backup screens let the user set a custom display name that
    // travels in the package manifest ("backed up from <name>"). Fork adds
    // the same members here; default falls back to [deviceName].

    const val CUSTOM_NAME_MAX_LENGTH = 40

    private const val KEY_CUSTOM_NAME = "customName"

    /** User-set name, or null when on the automatic [deviceName]. */
    fun customName(context: Context): String? {
        val prefs = encryptedPrefs(context)
        return prefs.getString(KEY_CUSTOM_NAME, null)?.takeIf { it.isNotBlank() }
    }

    /** What the UI shows by default ("Pixel 8 Pro · A3F7"). */
    fun automaticName(context: Context): String = deviceName(context)

    /** Persist the user-set name; blank clears the override. */
    fun setCustomName(context: Context, name: String) {
        encryptedPrefs(context).edit()
            .putString(KEY_CUSTOM_NAME, name.trim().take(CUSTOM_NAME_MAX_LENGTH).ifBlank { null })
            .apply()
    }

    /** Effective display name — custom when set, else automatic. */
    fun displayName(context: Context): String =
        customName(context) ?: automaticName(context)

    // T-android-keystore-aead-fail: self-healing wrapper handles
    // master-key invalidation on Samsung One UI / Android 16.
    private fun encryptedPrefs(context: Context) =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, PREFS_NAME)
}
