package com.dispatch.radio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * QR scan stub activity (dispatch-88k.7).
 *
 * Placeholder for PSK QR code scanning. Currently shows a "not yet implemented"
 * message. Replace body with ML Kit Barcode Scanning when Phase 3 QR pairing
 * is implemented (see SPEC Phase 3: "QR code pairing in console TUI").
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PSK = "extra_psk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }
}
