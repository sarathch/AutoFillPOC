package com.rama.service.autofillpoc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.autofill.AutofillManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.snackbar.Snackbar
import com.rama.service.autofillpoc.basic.FillPreferences

@RequiresApi(Build.VERSION_CODES.O)
class ConfigurationActivity : AppCompatActivity() {
    private val TAG = "ConfigurationActivity"
    private val REQUEST_CODE_SET_DEFAULT = 1
    private var mAutofillManager: AutofillManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuration)
        mAutofillManager = getSystemService(AutofillManager::class.java)
        val switch = findViewById<SwitchCompat>(R.id.sw_fill_config)
        switch.also {
            val prefs = FillPreferences.getInstance(this)
            it.isChecked = prefs.getAutoFillStatus()
        }.also {
            it.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    startService()
                } else {
                    disableService()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult(): req=$requestCode")
        when (requestCode) {
            REQUEST_CODE_SET_DEFAULT -> onDefaultServiceSet(resultCode)
        }
    }

    private fun disableService() {
        mAutofillManager?.let {
            if (it.hasEnabledAutofillServices()) {
                it.disableAutofillServices()
                FillPreferences.getInstance(this).setAutoFillStatus(false)
                Snackbar.make(findViewById(R.id.configuration_layout), R.string.autofill_disabled_message, Snackbar.LENGTH_LONG).show()
            } else {
                Log.d(TAG, "Sample service already disabled.")
            }
        }
    }

    private fun startService() {
        mAutofillManager?.let {
            if (!it.hasEnabledAutofillServices()) {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                intent.data = Uri.parse("package:com.rama.service.autofillpoc")
                Log.d(TAG, "enableService(): intent=$intent")
                startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT)
            } else {
                Log.d(TAG, "Sample service already enabled.")
            }
        }
    }

    private fun onDefaultServiceSet(resultCode: Int) {
        Log.d(TAG, "resultCode=$resultCode")
        when (resultCode) {
            RESULT_OK -> {
                Log.d(TAG, "Autofill service set.")
                FillPreferences.getInstance(this).setAutoFillStatus(true)
                Snackbar.make(findViewById(R.id.configuration_layout), R.string.autofill_service_set, Snackbar.LENGTH_SHORT)
                    .show()
            }
            RESULT_CANCELED -> {
                Log.d(TAG, "Autofill service not selected.")
                Snackbar.make(findViewById(R.id.configuration_layout), R.string.autofill_service_not_set, Snackbar.LENGTH_SHORT)
                    .show()
            }
        }
    }
}