package com.rama.service.autofillpoc.basic

import android.content.Context
import android.content.SharedPreferences
import android.service.autofill.FillResponse
import com.google.gson.Gson
import com.rama.service.autofillpoc.data.Credential

class FillPreferences (context: Context) {

    private val sharedPrefFile = "fillpreference"
    private val serviceStatusKey = "service_status_key"

    private val mPrefs: SharedPreferences = context.getSharedPreferences(sharedPrefFile, Context.MODE_PRIVATE)

    /**
     * Set Status of autofill service [FillResponse].
     */
    fun setAutoFillStatus(status: Boolean) =
        mPrefs.edit().also {
            it.putBoolean(serviceStatusKey, status)
        }.also {
            it.apply()
        }

    /**
     * Get Status of autofill service [FillResponse].
     */
    fun getAutoFillStatus() = mPrefs.getBoolean(serviceStatusKey, false)

    /**
     * Set auto fill credentials for specific domain/apps
     */
    fun setAutoFillCredentials(domain: String, credential: Credential) {
        mPrefs.edit().also {
            val credentialJson = Gson().toJson(credential)
            it.putString(domain, credentialJson)
        }.also {
            it.apply()
        }
    }

    /**
     * Get auto fill credentials for domain
     */
    fun getAutoFillCredentials(domain: String): Credential? {
        val credsJson =  mPrefs.getString(domain, null)
        credsJson?.let {
            return Gson().fromJson(credsJson, Credential::class.java)
        }
        return null
    }

    companion object {
        @Volatile private var INSTANCE: FillPreferences? = null

        fun getInstance(context: Context): FillPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FillPreferences(context).also { INSTANCE = it }
            }
    }
}