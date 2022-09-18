package com.rama.service.autofillpoc.basic

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.service.autofill.*
import android.util.ArrayMap
import android.util.Log
import android.util.Pair
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import com.rama.service.autofillpoc.R
import com.rama.service.autofillpoc.data.Credential
import com.rama.service.autofillpoc.supportedFieldsForAutoFill
import com.rama.service.autofillpoc.supportedWebApps
import java.util.*
import java.util.stream.Collectors

@RequiresApi(Build.VERSION_CODES.O)
class BasicAutoFillService : AutofillService() {

    private val TAG = "BasicAutoFillService"
    override fun onFillRequest(request: FillRequest, signal: CancellationSignal, callback: FillCallback) {
        Log.d(TAG, "OnFillRequest invoked")

        val fillContexts = request.fillContexts
        val structures = fillContexts.stream().map { obj: FillContext -> obj.structure }
            .collect(Collectors.toList())
        // Parse latest View Structure for auto fill
        val latestStructure = fillContexts[fillContexts.size - 1].structure
        val actPackageName = latestStructure.activityComponent?.packageName
        if (actPackageName.isNullOrEmpty()) {
            Log.wtf(TAG, "Package name can't be inferred. Weird. Dont continue")
            callback.onSuccess(null)
            return
        }
        val saveFlags = getSaveFlag(this, actPackageName)
        val finPackageName = updateIfWebApp(actPackageName, structures)
        Log.d(TAG, "save request processing for $finPackageName")
        // check if credentials exists for this package
        val credential = FillPreferences.getInstance(this).getAutoFillCredentials(finPackageName)
        // Fetch autofillable fields
        val fieldsMap = getAutofillableFields(latestStructure)
        if (fieldsMap.isNullOrEmpty()) {
            Log.d(TAG, "No Auto fillable fields exist")
            callback.onSuccess(null)
            return
        }
        Log.d(TAG, "autofillable fields:$fieldsMap")

        // create response
        val response = FillResponse.Builder()
        credential?.let {
            // previously saved credentials exists
            val dataset = Dataset.Builder()
            for (field in fieldsMap.entries) {
                val hint = field.key
                val autofillId = field.value
                val value = fetchValueForHint(it, hint)

                val presentation: RemoteViews? = newDatasetPresentation(packageName, value)
                presentation?.let { pr ->
                    dataset.setValue(autofillId!!, AutofillValue.forText(value), pr)
                }
            }
            toast("Loaded available details from $TAG")
            response.addDataset(dataset.build())
        } ?: run {
            toast("Save login details in $TAG to be securely auto filled.")
        }

        // 2.Add save info
        val ids = fieldsMap.values.filterNotNull()
        val requiredIds = ids.toTypedArray()
        response.setSaveInfo( // We only look to save valid fields
            SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                requiredIds
            ).run {
                if (saveFlags != null) {
                    setFlags(saveFlags)
                }
                build()
            }
        )

        // 3.Profit!
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "onSaveRequest()")
        val fillContexts = request.fillContexts
        val structures = fillContexts.stream().map { obj: FillContext -> obj.structure }
            .collect(Collectors.toList())
        // Parse latest View Structure for auto fill
        val latestStructure = fillContexts[fillContexts.size - 1].structure
        val actPackageName = latestStructure.activityComponent?.packageName
        if (actPackageName.isNullOrEmpty()) {
            Log.wtf(TAG, "Package name can't be inferred. Weird. Dont continue")
            callback.onFailure("Invalid application found")
            return
        }
        val finPackageName = updateIfWebApp(actPackageName, structures)
        Log.d(TAG, "save request processing for $finPackageName")
        // Fetch autofillable fields
        val datasetWithFilledAutofillFields = DatasetWithFilledAutofillFields(this, finPackageName)
        processForAutofillableFields(latestStructure, datasetWithFilledAutofillFields)
        // save auto fill fields to preferences
        datasetWithFilledAutofillFields.saveFilledFieldsToPrefs()
        toast("$TAG : saved successfully")
        callback.onSuccess()
    }

    /**
     * Helper method to get the [AssistStructure] associated with the latest request
     * in an autofill context.
     */
    private fun getLatestAssistStructure(@NonNull request: FillRequest): AssistStructure {
        val fillContexts = request.fillContexts
        return fillContexts[fillContexts.size - 1].structure
    }

    /**
     * Parses the [AssistStructure] representing the activity being autofilled, and returns a
     * map of autofillable fields (represented by their autofill ids) mapped by the hint associate
     * with them.
     *
     * An autofillable field is a [ViewNode] whose [.getHint] method is set
     */
    private fun getAutofillableFields(structure: AssistStructure): Map<String, AutofillId?>? {
        val fields: MutableMap<String, AutofillId?> = ArrayMap()
        val nodes = structure.windowNodeCount
        for (i in 0 until nodes) {
            val node = structure.getWindowNodeAt(i).rootViewNode
            addAutofillableFields(fields, node)
        }
        return fields
    }

    /**
     * Adds any autofillable view from the [ViewNode] and its descendants to the map.
     */
    private fun addAutofillableFields(fields: MutableMap<String, AutofillId?>, node: ViewNode) {
        // get the hint
        val hint = getHint(node)
        hint?.let {
            val id = node.autofillId
            // add fillable field if doesn't exist and a valid field that we consider
            if (!fields.containsKey(it) && supportedFieldsForAutoFill.contains(it)) {
                Log.v(TAG,"Setting hint '$it' on $id")
                fields[it] = id
            } else {
                Log.v(TAG, "Ignoring hint '$hint' on $id because it was already set")
            }
        }
        val childrenSize = node.childCount
        for (i in 0 until childrenSize) {
            addAutofillableFields(fields, node.getChildAt(i))
        }
    }

    /**
     * Parses the [AssistStructure] representing the activity being autofilled, and loads filled values
     * for the existing autofillable fields
     *
     * An autofillable field is a [ViewNode] whose [.getHint] method is set
     */
    private fun processForAutofillableFields(structure: AssistStructure, datasetWithFilledAutofillFields: DatasetWithFilledAutofillFields) {
        val nodes = structure.windowNodeCount
        for (i in 0 until nodes) {
            val node = structure.getWindowNodeAt(i).rootViewNode
            loadFilledFields(node, datasetWithFilledAutofillFields)
        }
    }

    /**
     * Loads filled in values for the view in the [ViewNode] and its descendants.
     */
    private fun loadFilledFields(node: ViewNode, datasetWithFilledAutofillFields: DatasetWithFilledAutofillFields) {
        // fetch hint
        val hint = getHint(node)
        hint?.let {
            if (supportedFieldsForAutoFill.contains(it)) {
                // for each valid field hint, parse autofill value
                parseAutofillFields(node, datasetWithFilledAutofillFields, hint)
            }
        }
        val childrenSize = node.childCount
        for (i in 0 until childrenSize) {
            loadFilledFields(node.getChildAt(i), datasetWithFilledAutofillFields)
        }
    }

    /**
     * Helper method to create a dataset presentation with the given text.
     */
    private fun newDatasetPresentation(packageName: String?, text: CharSequence?): RemoteViews? {
        val presentation = RemoteViews(packageName, R.layout.service_list_item)
        presentation.setTextViewText(R.id.text, text)
        presentation.setImageViewResource(R.id.icon, R.drawable.ic_key_credential)
        return presentation
    }

    private val saveFlagsForAccessibility =
        mapOf(
            "com.android.chrome" to SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE,
            "com.chrome.beta" to SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE,
            "com.chrome.dev" to SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE
        )

    private fun hasAccessibilityServiceEnabled(context: Context): Boolean {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).isNullOrEmpty()
    }

    private fun getSaveFlag(context: Context, appPackage: String): Int? =
        saveFlagsForAccessibility[appPackage]?.takeIf {
            hasAccessibilityServiceEnabled(context)
        }

    private fun getHint(node: ViewNode): String? {

        // First try the explicit autofill hints...
        val hints = node.autofillHints
        // we only care about the first hint
        val hint = hints?.get(0)?.lowercase(Locale.getDefault())
        hint?.let {
            Log.v(TAG,"Found valid autofill hint")
            return it
        } ?: run {
            // try some rudimentary heuristics based on other node properties
            val viewHint = node.hint ?: node.idEntry
            viewHint?.let {
                return inferHint(node, it)
            } ?: run {
                val t = node.text
                val className = node.className
                t?.let { hint ->
                    if (className.toString() == "EditText") {
                        return inferHint(node, hint.toString())
                    }
                }
            }
        }
        return null
    }

    /**
     * Uses heuristics to infer an autofill hint from a `string`.
     *
     * @return standard autofill hint, or `null` when it could not be inferred.
     */
    private fun inferHint(node: ViewNode, actualHint: String?): String? {
        if (actualHint == null) return null
        val hint = actualHint.lowercase(Locale.getDefault())
        if (hint.contains("label") || hint.contains("container")) {
            Log.v(TAG, "Ignoring 'label/container' hint: $hint")
            return null
        }
        if (hint.contains("password")) return View.AUTOFILL_HINT_PASSWORD
        if (hint.contains("username")
            || hint.contains("login") && hint.contains("id")
        ) return View.AUTOFILL_HINT_USERNAME
        if (hint.contains("email")) return View.AUTOFILL_HINT_EMAIL_ADDRESS
        if (hint.contains("name")) return View.AUTOFILL_HINT_NAME
        if (hint.contains("phone")) return View.AUTOFILL_HINT_PHONE

        // When everything else fails, return the full string - this is helpful to help app
        // developers visualize when autofill is triggered when it shouldn't (for example, in a
        // chat conversation window), so they can mark the root view of such activities with
        // android:importantForAutofill=noExcludeDescendants
        if (node.isEnabled && node.autofillType != View.AUTOFILL_TYPE_NONE) {
            Log.v(TAG, "Falling back to $actualHint")
            return actualHint
        }
        return null
    }

    private fun fetchValueForHint(credential: Credential, field: String): String {
        val validVal = when(field.lowercase(Locale.getDefault())) {
            "username" -> credential.username
            "password" -> credential.password
            "email"    -> credential.email
            else -> ""
        }
        return validVal?:""
    }

    private fun updateIfWebApp(packageName: String, structures: List<AssistStructure>): String {
        if (supportedWebApps.contains(packageName)) {
            val webDomainBuilder = StringBuilder()
            for (structure in structures) {
                val nodes = structure.windowNodeCount
                for (i in 0 until nodes) {
                    val viewNode = structure.getWindowNodeAt(i).rootViewNode
                    traverseRoot(viewNode, webDomainBuilder)
                }
            }
            if (webDomainBuilder.isNotEmpty()) {
                return webDomainBuilder.toString()
            }
        }
        return packageName
    }

    private fun traverseRoot(viewNode: ViewNode, webDomainBuilder: StringBuilder) {
        val webDomain = viewNode.webDomain
        webDomain?.let {
            webDomainBuilder.append(it)
        }
        val childrenSize = viewNode.childCount
        if (childrenSize > 0) {
            for (i in 0 until childrenSize) {
                traverseRoot(viewNode.getChildAt(i), webDomainBuilder)
            }
        }
    }

    private fun parseAutofillFields(viewNode: ViewNode, datasetWithFilledAutofillFields: DatasetWithFilledAutofillFields, hint: String) {
        val autofillValue = viewNode.autofillValue
        var textValue: String? = null
        if (autofillValue != null) {
            if (autofillValue.isText) {
                // Using toString of AutofillValue.getTextValue in order to save it to
                // SharedPreferences.
                textValue = autofillValue.textValue.toString()
            }
        }
        var pair: Pair<String, String>? = null
        textValue?.let {
            pair = Pair(hint, it)
        }
        datasetWithFilledAutofillFields.add(pair)
    }

    /**
     * Displays a toast with the given message.
     */
    private fun toast(@NonNull message: CharSequence) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}