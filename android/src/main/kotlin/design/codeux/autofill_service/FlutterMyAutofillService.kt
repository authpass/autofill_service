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
        PACKAGE_NAMES to webDomains.toList(),
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
private fun MutableList<AutofillHeuristic>.autofillHint(weight: Int, hint: String) =
    heuristic(weight) { autofillHints?.contains(hint) == true }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.idEntry(weight: Int, match: String) =
    heuristic(weight) { idEntry == match }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.htmlAttribute(weight: Int, attr: String, value: String) =
    heuristic(
        weight,
        "html[$attr=$value]"
    ) { htmlInfo?.attributes?.firstOrNull { it.first == attr && it.second == value } != null }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.defaults(hint: String, match: String) {
    autofillHint(900, hint)
    idEntry(800, match)
    heuristic(700) { idEntry?.lowercase()?.contains(match) == true }
}

@TargetApi(Build.VERSION_CODES.O)
enum class AutofillInputType(val heuristics: List<AutofillHeuristic>) {
    Password(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_PASSWORD, "password")
        htmlAttribute(400, "type", "password")
        heuristic(
            240,
            "text variation password"
        ) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) }
        heuristic(239) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) }
        heuristic(238) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) }
    }),
    Email(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_EMAIL_ADDRESS, "mail")
        htmlAttribute(400, "type", "mail")
        htmlAttribute(300, "name", "mail")
        heuristic(250, "hint=mail") {
            hint?.lowercase()?.contains("mail") == true
        }
    }),
    UserName(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_USERNAME, "user")
        htmlAttribute(400, "name", "user")
        htmlAttribute(400, "name", "username")
        heuristic(300) { hint?.lowercase()?.contains("login") == true }
    }),
}


fun Int?.hasFlag(flag: Int) = this != null && flag and this == flag
@Suppress("unused")
fun Int.withFlag(flag: Int) = this or flag
@Suppress("unused")
fun Int.minusFlag(flag: Int) = this and flag.inv()


data class MatchedField(val heuristic: AutofillHeuristic, val autofillId: AutofillId)

