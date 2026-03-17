package com.dispatch.radio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback patterns for radio events (dispatch-88k.8).
 *
 * Patterns:
 *   shortPulse     - PTT start / target change (50ms)
 *   confirmPulse   - PTT release (send confirm) (100ms)
 *   doublePulse    - empty transcript (2x 60ms)
 *   dispatchPulse  - agent dispatched (long-short: 120ms + 60ms)
 */
class HapticFeedback(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var enabled = true

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /** Short single pulse — used on target change. */
    fun shortPulse() {
        if (!enabled) return
        vibrate(50)
    }

    /** Confirm pulse — used after send. */
    fun confirmPulse() {
        if (!enabled) return
        vibrate(100)
    }

    /** Double pulse — used on empty transcript. */
    fun doublePulse() {
        if (!enabled) return
        vibratePattern(longArrayOf(0, 60, 80, 60))
    }

    /** Dispatch confirm — used when a new agent is dispatched (long-short pattern). */
    fun dispatchPulse() {
        if (!enabled) return
        vibratePattern(longArrayOf(0, 120, 60, 60))
    }

    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
