package com.vazbloke.joytype

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Defines the global scaling for haptic feedback.
 */
enum class HapticProfile(
    val scaleMultiplier: Float,  // For Android 11+ Premium LRA Primitives
    val maxAmplitude: Int,       // For Android 8-10 Fallbacks
    val ermDuration: Long        // ERM Base Spin-up time (Increased for dead-stop inertia)
) {
    STRONG(1.25f, 255, 50L),     // 50ms base
    MEDIUM(1.0f, 200, 35L),      // 35ms base
    LIGHT(0.6f, 120, 20L),       // 20ms base
    EXTRA_LIGHT(0.3f, 60, 10L),  
    OFF(0.0f, 0, 0L)
}

class HapticManager(context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // The active profile, easily updatable from a Settings ViewModel/Preferences
    var currentProfile: HapticProfile = HapticProfile.MEDIUM

    val isEnabled: Boolean
        get() = currentProfile != HapticProfile.OFF

/**
     * Standard typing click (Flick Attack)
     */
    fun click() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        // THE FIX: We add +10L to the base duration specifically to overcome dead-stop motor inertia.
        // At STRONG, this equals 60ms. At MEDIUM, 45ms.
        playEffect(primitive = VibrationEffect.Composition.PRIMITIVE_CLICK, basePrimitiveScale = 0.8f, fallbackDurationOffset = 20L)
    }

    /**
     * Heavy thud for hitting boundaries or radial limits.
     */
    fun thud() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        // Add a massive +25L so boundary collisions feel noticeably heavier than typing
        playEffect(primitive = VibrationEffect.Composition.PRIMITIVE_THUD, basePrimitiveScale = 1.0f, fallbackDurationOffset = 25L)
    }

    /**
     * Microscopic tick for mid-flick Swype heuristics or dial scrolling.
     */
    fun tick() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        // THE FIX: Apply an aggressive -15L negative offset!
        // Because the motor is already "warm" during scrolling, we choke the duration 
        // down to keep it from feeling muddy. At MEDIUM, this brings it right back to 20ms!
        playEffect(primitive = VibrationEffect.Composition.PRIMITIVE_TICK, basePrimitiveScale = 0.5f, fallbackDurationOffset = -15L)
    }

    /**
     * Ultra-light tick specifically for rapid key repetition (e.g., holding Backspace).
     */
    fun repeatTick() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            // High-End LRA: Scale the intensity down to a whisper (15% of the profile multiplier)
            val scaledIntensity = (0.15f * currentProfile.scaleMultiplier).coerceIn(0f, 1f)
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scaledIntensity)
                .compose()
            vibrator.vibrate(effect)
        } else {
            // THE FIX: Retro ERM Fallback.
            // DO NOT use currentProfile.ermDuration here! 
            // We force the duration to 1 millisecond and amplitude to 1 so the motor 
            // doesn't build inertia during a rapid-fire repeat cycle.
            fallbackVibrate(duration = 1L, amplitude = 1)
        }
    }

    /**
     * Master function to handle scaling and API routing cleanly.
     */
    private fun playEffect(primitive: Int, basePrimitiveScale: Float, fallbackDurationOffset: Long = 0L) {
        
        // --- THE "HIGH-END PREMIUM DEVICE" IF STATEMENT ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(primitive)) {
            
            // 1. We completely ignore the 'ermDuration' variable here.
            // 2. We let the premium LRA driver handle the physical timing.
            // 3. We only adjust the INTENSITY using the scaleMultiplier (e.g., 1.25f for STRONG).
            
            val finalScale = (basePrimitiveScale * currentProfile.scaleMultiplier).coerceIn(0f, 1f)
            val effect = VibrationEffect.startComposition()
                .addPrimitive(primitive, finalScale)
                .compose()
            vibrator.vibrate(effect)
            
        } else {
            // --- THE "RETRO HANDHELD / ERM" ELSE STATEMENT ---
            
            // 1. Android confirms this device lacks premium primitive support.
            // 2. NOW we apply the 35ms ermDuration and manual amplitude to force the cheap motor to spin.
            
            val finalAmplitude = (currentProfile.maxAmplitude * basePrimitiveScale).toInt().coerceIn(1, 255)
            val finalDuration = (currentProfile.ermDuration + fallbackDurationOffset).coerceAtLeast(1L)
            
            fallbackVibrate(finalDuration, finalAmplitude)
        }
    }

    private fun fallbackVibrate(duration: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}