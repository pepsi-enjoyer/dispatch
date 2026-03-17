package com.dispatch.radio

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen (dispatch-88k.7).
 *
 * - Console IP + port
 * - Pre-shared key (manual entry)
 * - Haptic feedback toggle (default on)
 * - Confirm before send toggle (default off)
 * - Keep screen on toggle (default on)
 * - Speech recognition locale (default en-AU)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: RadioSettings

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etPsk: EditText
    private lateinit var etLocale: EditText
    private lateinit var cbHaptic: CheckBox
    private lateinit var cbConfirm: CheckBox
    private lateinit var cbScreenOn: CheckBox
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = RadioSettings(this)

        etHost = findViewById(R.id.et_host)
        etPort = findViewById(R.id.et_port)
        etPsk = findViewById(R.id.et_psk)
        etLocale = findViewById(R.id.et_locale)
        cbHaptic = findViewById(R.id.cb_haptic)
        cbConfirm = findViewById(R.id.cb_confirm)
        cbScreenOn = findViewById(R.id.cb_screen_on)
        btnSave = findViewById(R.id.btn_save)

        loadSettings()

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun loadSettings() {
        etHost.setText(settings.consoleHost)
        etPort.setText(settings.consolePort.toString())
        etPsk.setText(settings.psk)
        etLocale.setText(settings.speechLocale)
        cbHaptic.isChecked = settings.hapticEnabled
        cbConfirm.isChecked = settings.confirmBeforeSend
        cbScreenOn.isChecked = settings.keepScreenOn
    }

    private fun saveSettings() {
        settings.consoleHost = etHost.text.toString().trim().ifEmpty { "192.168.1.1" }
        settings.consolePort = etPort.text.toString().trim().toIntOrNull() ?: 9800
        settings.psk = etPsk.text.toString().trim()
        settings.speechLocale = etLocale.text.toString().trim().ifEmpty { "en-AU" }
        settings.hapticEnabled = cbHaptic.isChecked
        settings.confirmBeforeSend = cbConfirm.isChecked
        settings.keepScreenOn = cbScreenOn.isChecked

        // Apply keep screen on immediately
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setResult(RESULT_OK)
        finish()
    }
}
