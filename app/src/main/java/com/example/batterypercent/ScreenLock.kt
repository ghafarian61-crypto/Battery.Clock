package com.example.batterypercent

import android.accessibilityservice.AccessibilityService
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Switches the screen off like a press of the power button.
 *  - Android 9+ (API 28+): an accessibility service performs GLOBAL_ACTION_LOCK_SCREEN (fingerprint still works).
 *  - Android 8 (API 26-27, e.g. Galaxy C9 Pro): no such action exists, so a device administrator with the single
 *    "force lock" policy calls lockNow().
 * It reads nothing from the screen and does nothing else.
 */
object ScreenLock {
    fun adminComponent(ctx: Context) = ComponentName(ctx, LockAdminReceiver::class.java)

    fun adminActive(ctx: Context): Boolean = try {
        ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(ctx))
    } catch (e: Exception) {
        false
    }

    /** The accessibility service is switched on in the system settings. */
    fun accessibilityEnabled(ctx: Context): Boolean {
        val list = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Exception) {
            null
        } ?: return false
        val me = ComponentName(ctx, LockAccessibilityService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The permission needed on this Android version has been given. */
    fun isGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) accessibilityEnabled(ctx) else adminActive(ctx)

    /** Switches the screen off now. False when that is not possible (no permission yet). */
    fun lock(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LockAccessibilityService.instance
                ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false
        } else if (adminActive(ctx)) {
            ctx.getSystemService(DevicePolicyManager::class.java).lockNow()
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }
}

/** Does nothing but wait for [ScreenLock.lock]; it listens to no events. */
class LockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: LockAccessibilityService? = null
    }
}

/** Only used on Android 8: the "force lock" administrator. */
class LockAdminReceiver : DeviceAdminReceiver()
