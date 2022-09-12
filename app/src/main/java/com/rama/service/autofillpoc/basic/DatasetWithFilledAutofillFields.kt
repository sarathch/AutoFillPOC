package com.rama.service.autofillpoc.basic

import android.content.Context
import android.util.Pair
import com.rama.service.autofillpoc.data.Credential

class DatasetWithFilledAutofillFields(context: Context, private val packageName: String) {

    private val mPrefs = FillPreferences.getInstance(context)

    private var filledAutoFillFields = mutableMapOf<String, String>()

    fun add(filledAutofillField: Pair<String, String>?) {
        if (filledAutofillField != null) {
            filledAutoFillFields[filledAutofillField.first] = filledAutofillField.second
        }
    }

    fun saveFilledFieldsToPrefs() {
        val fields = arrayOfNulls<String>(3)
        var credential = mPrefs.getAutoFillCredentials(packageName)
        // load previously existed fields in preferences
        credential?.let {
            fields[0] = it.username
            fields[1] = it.password
            fields[2] = it.email
        }
        for (key in filledAutoFillFields.keys) {
            // update fields with latest filled fields
            when(key) {
                "username" -> fields[0] = filledAutoFillFields[key]
                "password" -> fields[1] = filledAutoFillFields[key]
                "email"    -> fields[2] = filledAutoFillFields[key]
            }
        }

        credential = Credential(fields[0], fields[1], fields[2])
        mPrefs.setAutoFillCredentials(packageName, credential)
    }
}