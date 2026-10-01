package com.reamicro.fix.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.window.SplashScreenView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * ReaMicro Extend's platform splash transition.
 *
 * Only supplies an exit animation. The system owns entry, first-frame readiness,
 * background and foreground day/night resource selection.
 * API 26–30 keep their default starting window; no synthetic splash Activity/overlay.
 */
internal object ModuleSplashScreen {
    private const val EXIT_DURATION_MS = 200L

    /** Call before switching to the content theme and before super.onCreate. */
    fun install(activity: ComponentActivity, restored: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Controller(activity, restored).attach()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Controller(
        private val activity: ComponentActivity,
        private val restored: Boolean,
    ) : DefaultLifecycleObserver {
        private var animator: ObjectAnimator? = null
        private var splashView: SplashScreenView? = null
        private var finished = false

        fun attach() {
            activity.lifecycle.addObserver(this)
            activity.splashScreen.setOnExitAnimationListener { view ->
                // A callback queued before stop/destroy must not retain or animate its view.
                if (finished || activity.isFinishing || activity.isDestroyed ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    view.remove()
                    finish()
                    return@setOnExitAnimationListener
                }
                splashView = view
                if (restored || !ValueAnimator.areAnimatorsEnabled()) {
                    finish()
                    return@setOnExitAnimationListener
                }
                val exit = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
                    // Monotonic alpha in [0,1], unlike an anticipate interpolator.
                    interpolator = DecelerateInterpolator()
                    duration = EXIT_DURATION_MS
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = finish()
                        override fun onAnimationCancel(animation: Animator) = finish()
                    })
                }
                // Assign before start: disabled/zero-scale animations may finish synchronously.
                animator = exit
                exit.start()
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            activity.splashScreen.clearOnExitAnimationListener()
            val exit = animator
            animator = null
            exit?.removeAllListeners()
            exit?.cancel()
            splashView?.remove()
            splashView = null
            activity.lifecycle.removeObserver(this)
        }

        override fun onStop(owner: LifecycleOwner) = finish()
        override fun onDestroy(owner: LifecycleOwner) = finish()
    }
}
