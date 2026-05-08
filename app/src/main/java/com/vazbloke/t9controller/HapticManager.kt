package com.vazbloke.t9controller

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class HapticManager(context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // Public properties that the Service will update when settings change
    var isEnabled: Boolean = true
    var customDuration: Long = 15L

    /**
     * Standard typing click.
     */
    fun click() {
        if (!isEnabled || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            fallbackVibrate(customDuration)
        }
    }

    /**
     * Heavy thud for hitting boundaries or radial limits.
     */
    fun thud() {
        if (!isEnabled || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            fallbackVibrate(customDuration + 25L)
        }
    }

    /**
     * Microscopic tick for mid-flick Swype heuristics or dial scrolling.
     */
    fun tick() {
        if (!isEnabled || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            // Cut the user's custom duration in half for a lighter feel
            val tickDuration = kotlin.math.max(1L, customDuration / 2L)
            fallbackVibrate(tickDuration)
        }
    }

     /**
     * Ultra-light tick specifically for rapid key repetition (e.g., holding Backspace).
     */
    fun repeatTick() {
        if (!isEnabled || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.15f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            // THE FIX: For ERM motors, drop the amplitude down to a whisper-quiet 10 (out of 255), 
            // and cap the duration to a 2ms flicker so the motor doesn't build momentum.
            fallbackVibrate(duration = 1L, amplitude = 10) 
        }
    }

    private fun fallbackVibrate(duration: Long, amplitude: Int = 255) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Now we pass the custom amplitude instead of hardcoding 255
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}