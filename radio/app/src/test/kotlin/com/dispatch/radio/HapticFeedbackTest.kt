package com.dispatch.radio

import android.content.Context
import android.os.Vibrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import android.os.VibrationEffect

@RunWith(MockitoJUnitRunner::class)
class HapticFeedbackTest {

    @Mock lateinit var context: Context
    @Mock lateinit var vibrator: Vibrator

    @Before
    fun setUp() {
        @Suppress("DEPRECATION")
        `when`(context.getSystemService(Context.VIBRATOR_SERVICE)).thenReturn(vibrator)
        `when`(vibrator.hasVibrator()).thenReturn(true)
    }

    @Test
    fun `enabled is true by default`() {
        val haptics = HapticFeedback(context)
        assertTrue(haptics.enabled)
    }

    @Test
    fun `vibrate is suppressed when enabled is false`() {
        val haptics = HapticFeedback(context)
        haptics.enabled = false
        haptics.listeningStart()
        verify(vibrator, never()).vibrate(any<VibrationEffect>())
    }

    @Test
    fun `shortPulse delegates to targetChange`() {
        val haptics = HapticFeedback(context)
        haptics.enabled = false
        // Just verify no exception is thrown and the delegation compiles.
        haptics.shortPulse()
        haptics.targetChange()
    }

    @Test
    fun `all semantic methods callable without exception when enabled is false`() {
        val haptics = HapticFeedback(context)
        haptics.enabled = false
        haptics.listeningStart()
        haptics.sendConfirm()
        haptics.emptyTranscript()
        haptics.targetChange()
        haptics.dispatchConfirm()
    }

    @Test
    fun `can toggle enabled at runtime`() {
        val haptics = HapticFeedback(context)
        haptics.enabled = true
        assertTrue(haptics.enabled)
        haptics.enabled = false
        assertFalse(haptics.enabled)
    }

    private fun <T> any(): T = org.mockito.Mockito.any<T>()
}
