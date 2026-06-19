package online.automint.app.splash

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

class SplashController(
    private val overlay: View,
    private val aurora: View,
    private val logo: View,
    private val logoHalo: View,
    private val ringFill: View,
    private val wordmarkContainer: ViewGroup,
) {

    private val runningAnimators = mutableListOf<Animator>()
    private var dismissed = false

    fun start() {
        val density = Resources.getSystem().displayMetrics.density

        aurora.alpha = 0f
        val auroraIn = ObjectAnimator.ofFloat(aurora, View.ALPHA, 0f, 1f).apply {
            duration = 900L
            startDelay = 200L
        }
        val auroraBreath = ObjectAnimator.ofPropertyValuesHolder(
            aurora,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f),
        ).apply {
            duration = 3000L
            startDelay = 1100L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        track(auroraIn, auroraBreath)
        auroraIn.start()
        auroraBreath.start()

        logo.alpha = 0f
        logo.scaleX = 0.9f
        logo.scaleY = 0.9f
        val logoIn = ObjectAnimator.ofPropertyValuesHolder(
            logo,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1f),
        ).apply {
            duration = 520L
            startDelay = 80L
            interpolator = EASE_OUT_EXPO
        }
        val logoFloat = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 0f, -2f * density).apply {
            duration = 2300L
            startDelay = 1100L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        val haloBreath = ObjectAnimator.ofPropertyValuesHolder(
            logoHalo,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        ).apply {
            duration = 1600L
            startDelay = 800L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        track(logoIn, logoFloat, haloBreath)
        logoIn.start()
        logoFloat.start()
        haloBreath.start()

        ringFill.alpha = 0f
        val ringIn = ObjectAnimator.ofFloat(ringFill, View.ALPHA, 0f, 1f).apply {
            duration = 520L
            startDelay = 260L
            interpolator = EASE_OUT_EXPO
        }
        val ringSpin = ObjectAnimator.ofFloat(ringFill, View.ROTATION, 0f, 360f).apply {
            duration = 9000L
            startDelay = 520L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        track(ringIn, ringSpin)
        ringIn.start()
        ringSpin.start()

        for (i in 0 until wordmarkContainer.childCount) {
            val letter = wordmarkContainer.getChildAt(i) as? TextView ?: continue
            letter.alpha = 0f
            letter.translationY = 10f * density
            val drop = ObjectAnimator.ofPropertyValuesHolder(
                letter,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 10f * density, 0f),
            ).apply {
                duration = 580L
                startDelay = 440L + i * 55L
                interpolator = EASE_OUT_EXPO
            }
            track(drop)
            drop.start()
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        overlay.animate()
            .alpha(0f)
            .setDuration(400L)
            .withEndAction {
                overlay.visibility = View.GONE
                cancelAll()
            }
            .start()
    }

    private fun track(vararg animators: Animator) {
        runningAnimators.addAll(animators)
    }

    private fun cancelAll() {
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
    }

    companion object {
        private val EASE_OUT_EXPO = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    }
}
