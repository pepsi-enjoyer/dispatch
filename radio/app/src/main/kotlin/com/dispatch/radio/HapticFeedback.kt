package com.dispatch.radio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback for Dispatch Radio events.
 *
 * Distinct vibration patterns for each interaction type:
 *  - [listeningStart]  : single short pulse (40 ms) — PTT key down
 *  - [sendConfirm]     : single firm pulse (80 ms) — PTT key up, transcript sent
 *  - [emptyTranscript] : double-pulse (30+30 ms) — PTT key up, nothing recorded
 *  - [targetChange]    : crisp tick (20 ms) — volume-up target cycle
 *  - [dispatchConfirm] : ascending triple-pulse — new agent dispatched
 *
 * Set [enabled] = false to silence all patterns.
 * Defaults to enabled; wire to [RadioSettings.hapticEnabled] on startup.
 */
class HapticFeedback(context: Context) {

    var enabled: Boolean = true

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** Single short pulse (40 ms). PTT key down — listening starts. */
    fun listeningStart() = vibrate(PATTERN_LISTENING)

    /** Single firm pulse (80 ms). PTT key up — transcript parsed and sent. */
    fun sendConfirm() = vibrate(PATTERN_CONFIRM)

    /** Double-pulse (30 + 30 ms). PTT key up — transcript was empty, nothing sent. */
    fun emptyTranscript() = vibrate(PATTERN_DOUBLE)

    /** Crisp tick (20 ms). Volume Up short press — target cycled to next agent. */
    fun targetChange() = vibrate(PATTERN_TICK)

    /** Ascending triple-pulse. Confirms a new agent was dispatched. */
    fun dispatchConfirm() = vibrate(PATTERN_DISPATCH)

    // --- Compatibility aliases for callers using the generic method names ---

    /** Alias: calls [targetChange]. Used by [VolumeUpHandler]. */
    fun shortPulse() = targetChange()

    /** Alias: calls [sendConfirm]. */
    fun confirmPulse() = sendConfirm()

    /** Alias: calls [emptyTranscript]. */
    fun doublePulse() = emptyTranscript()

    /** Alias: calls [dispatchConfirm]. */
    fun dispatchPulse() = dispatchConfirm()

    private fun vibrate(pattern: LongArray) {
        if (!enabled) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
    }

    companion object {
        private const val NO_REPEAT = -1

        // 40 ms — short single pulse (PTT start / listening start)
        private val PATTERN_LISTENING = longArrayOf(0, 40)

        // 80 ms — firm single pulse (send confirm)
        private val PATTERN_CONFIRM = longArrayOf(0, 80)

        // 30 ms on · 70 ms off · 30 ms on (empty transcript double-pulse)
        private val PATTERN_DOUBLE = longArrayOf(0, 30, 70, 30)

        // 20 ms — crisp tick (target change, shorter/different feel from PTT start)
        private val PATTERN_TICK = longArrayOf(0, 20)

        // 25 ms · 55 ms off · 35 ms · 55 ms off · 55 ms — ascending triple-pulse (dispatch confirm)
        private val PATTERN_DISPATCH = longArrayOf(0, 25, 55, 35, 55, 55)
    }
}
