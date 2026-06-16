package com.reamicro.fix.hook

import android.app.Activity
import android.content.pm.ActivityInfo
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XposedBridge

object RotationOrientationController {
    fun apply(activity: Activity, snapshot: ModuleSettingsSnapshot) {
        val orientation = requestedOrientation(snapshot)
        if (activity.requestedOrientation == orientation) return
        activity.requestedOrientation = orientation
        XposedBridge.log("$LOG_PREFIX requestedOrientation=$orientation")
    }

    fun requestedOrientation(snapshot: ModuleSettingsSnapshot): Int {
        if (!snapshot.canApplyRotation) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val reverse = snapshot.rotationReverseEnabled
        return when {
            snapshot.rotation.autoEnabled -> if (reverse) {
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
            snapshot.rotation.portraitLockEnabled -> if (reverse) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            snapshot.rotation.landscapeLockEnabled -> if (reverse) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private const val LOG_PREFIX = "ReaMicro LSP rotation"
}
