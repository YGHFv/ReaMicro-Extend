package com.reamicro.fix.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.animation.AnticipateInterpolator

internal class ModuleSplashExit(private val activity: Activity) {
    private var animator: ObjectAnimator? = null
    private var removeSplash: (() -> Unit)? = null

    fun install() {
        if (Build.VERSION.SDK_INT < 31) return
        activity.splashScreen.setOnExitAnimationListener { splash ->
            removeSplash = { splash.remove() }
            if (activity.isFinishing || activity.isDestroyed || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                release()
            } else {
                animator = ObjectAnimator.ofFloat(splash, View.ALPHA, 1f, 0f).apply {
                    duration = EXIT_DURATION_MS
                    interpolator = AnticipateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = release()
                        override fun onAnimationCancel(animation: Animator) = release()
                    })
                    start()
                }
            }
        }
    }

    private fun release() {
        val remove = removeSplash
        removeSplash = null
        animator = null
        remove?.invoke()
    }

    fun dispose() {
        animator?.cancel()
        release()
        if (Build.VERSION.SDK_INT >= 31) activity.splashScreen.clearOnExitAnimationListener()
    }

    companion object { const val EXIT_DURATION_MS = 500L }
}
