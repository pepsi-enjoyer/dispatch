package com.dispatch.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dispatch.radio.model.Agent

/**
 * Handles Volume Up key events with tap/hold distinction.
 *
 * Tap  (< [HOLD_THRESHOLD_MS]): cycle to the next saved connection profile
 *   and reconnect to the console.
 *
 * Hold (>= [HOLD_THRESHOLD_MS]): show agent status overlay listing all agents
 *   with their busy/idle state. Stays visible for the entire hold duration.
 *
 * Usage:
 *   - Call [onKeyDown] from Activity.onKeyDown for KEYCODE_VOLUME_UP
 *   - Call [onKeyUp] from Activity.onKeyUp for KEYCODE_VOLUME_UP
 *   - Both return true to consume the event and suppress system volume UI
 */
class VolumeUpHandler(
    private val context: Context,
    private val haptics: HapticFeedback,
    private val onTap: () -> Unit,
) {
    private var isKeyDown = false
    private var overlayShown = false
    private var statusOverlay: AgentStatusOverlay? = null
    private val handler = Handler(Looper.getMainLooper())

    // Stashed references so the delayed runnable can show the overlay
    private var pendingAgents: List<Agent> = emptyList()

    private val showOverlayRunnable = Runnable {
        if (isKeyDown && VolumeKeyBridge.isActivityInForeground) {
            statusOverlay = AgentStatusOverlay(context).also { it.show(pendingAgents) }
            overlayShown = true
        }
    }

    /** Call from Activity.onKeyDown for KEYCODE_VOLUME_UP. Returns true to consume. */
    fun onKeyDown(agents: List<Agent>): Boolean {
        if (isKeyDown) return true // Ignore key-repeat events while held
        isKeyDown = true
        overlayShown = false
        pendingAgents = agents
        handler.postDelayed(showOverlayRunnable, HOLD_THRESHOLD_MS)
        return true
    }

    /** Call from Activity.onKeyUp for KEYCODE_VOLUME_UP. Returns true to consume. */
    fun onKeyUp(): Boolean {
        handler.removeCallbacks(showOverlayRunnable)
        val wasHold = overlayShown
        isKeyDown = false
        overlayShown = false
        pendingAgents = emptyList()

        if (wasHold) {
            haptics.shortPulse()
            statusOverlay?.dismiss()
            statusOverlay = null
        } else {
            // Short tap — cycle profile
            onTap()
        }
        return true
    }

    companion object {
        private const val HOLD_THRESHOLD_MS = 300L
    }
}
