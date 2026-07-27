package design.codeux.autofill_service

import android.annotation.TargetApi
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.os.TransactionTooLargeException
import android.service.autofill.*
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import mu.KotlinLogging
import org.json.JSONArray
import org.json.JSONObject


private val logger = KotlinLogging.logger {}

@RequiresApi(api = Build.VERSION_CODES.O)
class FlutterMyAutofillService : AutofillService() {

    private lateinit var autofillPreferenceStore: AutofillPreferenceStore
    private var unlockLabel = "Autofill"

    override fun onCreate() {
        super.onCreate()
        logger.debug { "Autofill service was created." }
        autofillPreferenceStore = AutofillPreferenceStore.getInstance(applicationContext)
    }

    override fun onConnected() {
        super.onConnected()
        logger.debug("onConnected.")
        val self = ComponentName(this, javaClass)

        val metaData = packageManager.getServiceInfo(self, PackageManager.GET_META_DATA).metaData
        metaData.getString("design.codeux.autofill_service.unlock_label")?.let {
            unlockLabel = it
        }
        logger.info("Unlock label will be $unlockLabel")
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        logger.info { "Got fill request $request" }
//        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)

        val context = request.fillContexts.last()
        val parser = AssistStructureParser(context.structure)

        var useLabel = unlockLabel
        if (parser.fieldIds[AutofillInputType.Password].isNullOrEmpty()) {
            val detectedFields = parser.fieldIds.flatMap { it.value }.size
            logger.debug { "got autofillPreferences: ${autofillPreferenceStore.autofillPreferences}" }
            if (!autofillPreferenceStore.autofillPreferences.enableDebug) {
                callback.onSuccess(null)
                return
            }
            useLabel = "Debug: No password fields detected ($detectedFields total)."
        }

        logger.debug { "Trying to fetch package info." }
        val activityName =
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).run {
                metaData.getString("design.codeux.autofill_service.ACTIVITY_NAME")
            } ?: "design.codeux.authpass.MainActivity"
        logger.debug("got activity $activityName")

        val startIntent = Intent()
        // TODO: Figure this out how to do this without hard coding everything..
        startIntent.setClassName(applicationContext, activityName)
        startIntent.action = Intent.ACTION_RUN
        //"design.codeux.autofill_service_example.MainActivity")
//        val startIntent = Intent(Intent.ACTION_MAIN).apply {
//                                `package` = applicationContext.packageName
//                    logger.debug { "Creating custom intent." }
//                }
//        startIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startIntent.putExtra("route", "/autofill")
        startIntent.putExtra("initial_route", "/autofill")
        parser.packageName.firstOrNull()?.let {
            startIntent.putExtra(
                "autofillPackageName",
                it
            )
        }
        if (parser.webDomain.size > 1) {
            logger.warn { "Found multiple autofillWebDomain: ${parser.webDomain}" }
        }
        parser.webDomain
            .firstOrNull { it.domain.isNotBlank() }
            ?.let { startIntent.putExtra("autofillWebDomain", it.domain) }
        // We serialize to string, because the Parcelable made some serious problems.
        // https://stackoverflow.com/a/39478479/109219
        startIntent.putExtra(
            AutofillMetadata.EXTRA_NAME,
            AutofillMetadata(parser.packageName, parser.webDomain).toJsonString()
        )
        startIntent.putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, context.structure)
//        startIntent.putParcelableArrayListExtra("autofillIds", ArrayList(parser.autoFillIds))
        val intentSender: IntentSender = PendingIntent.getActivity(
            this,
            0,
            startIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        ).intentSender
        logger.debug { "startIntent:$startIntent (${startIntent.extras}) - sender: $intentSender" }

        val autoFillIds = parser.autoFillIds.distinct()

        // Build a FillResponse object that requires authentication.
        val fillResponseBuilder: FillResponse.Builder = FillResponse.Builder()
            .setAuthentication(
                autoFillIds.toTypedArray(),
                intentSender,
                RemoteViewsHelper.viewsWithAuth(packageName, useLabel)
            )
        logger.info {
            "remoteView for packageName: $packageName -- " +
                    "detected autofill packageName: ${parser.packageName} " +
                    "webDomain: ${parser.webDomain}" +
                    "autoFillIds: ${autoFillIds.size}"
        }

        val fillResponse = fillResponseBuilder.build()

        try {
            callback.onSuccess(fillResponse)
        } catch (e: TransactionTooLargeException) {
            throw RuntimeException(
                "Too many auto fill ids discovered ${autoFillIds.size} for " +
                        "${parser.webDomain},  ${parser.packageName}",
                e
            )
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        logger.info { "onSaveRequest. but not yet implemented." }
        callback.onFailure("Not implemented")
    }


}

data class AutofillMetadata(
    val packageNames: Set<String>,
    val webDomains: Set<WebDomain>
) {
    companion object {
        const val EXTRA_NAME = "AutofillMetadata"
        private const val PACKAGE_NAMES = "packageNames"
        private const val WEB_DOMAINS = "webDomains"

        fun fromJsonString(json: String) =
            JSONObject(json).run {
                AutofillMetadata(
                    packageNames = optJSONArray(PACKAGE_NAMES).map { array, index ->
                        array.getString(index)
                    }.toSet(),
                    webDomains = optJSONArray(WEB_DOMAINS).map { array, index ->
                        WebDomain.fromJson(array.getJSONObject(index))
                    }.toSet()
                )
            }
    }

    fun toMap(): Map<Any, Any> = mapOf(
        PACKAGE_NAMES to packageNames.toList(),
        WEB_DOMAINS to webDomains.map { it.toMap() },
    )

    fun toJsonString(): String = JSONObject(toMap()).toString()
}

fun <T> JSONArray.map(f: (array: JSONArray, index: Int) -> T): List<T> {
    return (0 until length()).map { index ->
        f(this, index)
    }
}


/**
 * This is a class containing helper methods for building Autofill Datasets and Responses.
 */
object RemoteViewsHelper {

    fun viewsWithAuth(packageName: String, text: String): RemoteViews {
        return simpleRemoteViews(packageName, text, R.drawable.ic_lock_black_24dp)
    }

    fun viewsWithNoAuth(packageName: String, text: String): RemoteViews {
        return simpleRemoteViews(packageName, text, R.drawable.ic_person_black_24dp)
    }

    private fun simpleRemoteViews(
        packageName: String, remoteViewsText: String,
        @DrawableRes drawableId: Int
    ): RemoteViews {
        val presentation = RemoteViews(
            packageName,
            R.layout.multidataset_service_list_item
        )
        presentation.setTextViewText(R.id.text, remoteViewsText)
        presentation.setImageViewResource(R.id.icon, drawableId)
        return presentation
    }
}

data class AutofillHeuristic(
    val weight: Int,
    val message: String?,
    val predicate: AssistStructure.ViewNode.(node: AssistStructure.ViewNode) -> Boolean
)

private fun MutableList<AutofillHeuristic>.heuristic(
    weight: Int,
    message: String? = null,
    predicate: AssistStructure.ViewNode.(node: AssistStructure.ViewNode) -> Boolean
) =
    add(AutofillHeuristic(weight, message, predicate))


@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.autofillHint(weight: Int, vararg hints: String) =
    heuristic(weight, "hint=${hints.joinToString("|")}") {
        autofillHints?.any { actual -> hints.any { it.equals(actual, ignoreCase = true) } } == true
    }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.idEntry(weight: Int, match: String) =
    heuristic(weight) { idEntry == match }

@TargetApi(Build.VERSION_CODES.O)
private fun AssistStructure.ViewNode.htmlAttribute(attr: String): String? =
    htmlInfo?.attributes?.firstOrNull { it.first == attr }?.second

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.htmlAttribute(weight: Int, attr: String, value: String) =
    heuristic(weight, "html[$attr=$value]") {
        htmlAttribute(attr).equals(value, ignoreCase = true)
    }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.htmlAttributeContains(
    weight: Int,
    attr: String,
    value: String
) =
    heuristic(weight, "html[$attr*=$value]") {
        htmlAttribute(attr)?.lowercase()?.contains(value) == true
    }

/**
 * Chromium based browsers pass their own field classification along with the virtual view
 * structure. The values are the (stable) names of chromium's `FieldType` enum.
 * https://chromium.googlesource.com/chromium/src/+/HEAD/components/android_autofill/README.md
 */
@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.browserPrediction(weight: Int, types: Set<String>) =
    BROWSER_PREDICTION_ATTRIBUTES.forEachIndexed { index, attr ->
        heuristic(weight - index, "html[$attr in $types]") {
            val prediction = htmlAttribute(attr)?.uppercase()
            prediction != null && types.contains(prediction)
        }
    }

private val BROWSER_PREDICTION_ATTRIBUTES = listOf(
    "computed-autofill-hints",
    "ua-autofill-hints",
    "crowdsourcing-autofill-hints",
)

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.defaults(hint: String, match: String) {
    autofillHint(900, hint)
    idEntry(800, match)
    heuristic(700) { idEntry?.lowercase()?.contains(match) == true }
}

@TargetApi(Build.VERSION_CODES.O)
enum class AutofillInputType(val heuristics: List<AutofillHeuristic>) {
    // Password must always win over UserName/Email on the same node, so all of its
    // heuristics are weighted above the ones which only look at names and hints.
    Password(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_PASSWORD, "password")
        // html autocomplete attribute, as forwarded by browsers.
        autofillHint(890, "current-password", "new-password")
        htmlAttribute(880, "type", "password")
        browserPrediction(
            870, setOf(
                "PASSWORD",
                "ACCOUNT_CREATION_PASSWORD",
                "NEW_PASSWORD",
                "PROBABLY_NEW_PASSWORD",
                "CONFIRMATION_PASSWORD",
            )
        )
        heuristic(
            240,
            "text variation password"
        ) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) }
        heuristic(239) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) }
        heuristic(238) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) }
    }),
    Email(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_EMAIL_ADDRESS, "mail")
        autofillHint(600, "email")
        htmlAttribute(500, "type", "email")
        browserPrediction(450, setOf("EMAIL_ADDRESS", "USERNAME_AND_EMAIL_ADDRESS"))
        htmlAttributeContains(400, "name", "mail")
        htmlAttributeContains(390, "id", "mail")
        heuristic(250, "hint=mail") {
            hint?.lowercase()?.contains("mail") == true
        }
        htmlAttributeContains(240, "label", "mail")
    }),
    UserName(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_USERNAME, "user")
        browserPrediction(
            450, setOf(
                "USERNAME",
                "USERNAME_AND_EMAIL_ADDRESS",
                "SINGLE_USERNAME",
                "SINGLE_USERNAME_FORGOT_PASSWORD",
                "SINGLE_USERNAME_WITH_INTERMEDIATE_VALUES",
            )
        )
        htmlAttributeContains(400, "name", "user")
        htmlAttributeContains(390, "id", "user")
        htmlAttributeContains(380, "name", "login")
        htmlAttributeContains(370, "id", "login")
        heuristic(300) { hint?.lowercase()?.contains("login") == true }
        htmlAttributeContains(230, "label", "user")
        htmlAttributeContains(220, "label", "login")
    }),
}


fun Int?.hasFlag(flag: Int) = this != null && flag and this == flag
@Suppress("unused")
fun Int.withFlag(flag: Int) = this or flag
@Suppress("unused")
fun Int.minusFlag(flag: Int) = this and flag.inv()


data class MatchedField(val heuristic: AutofillHeuristic, val autofillId: AutofillId)

