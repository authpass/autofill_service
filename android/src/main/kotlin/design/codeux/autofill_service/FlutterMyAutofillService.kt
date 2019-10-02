package design.codeux.autofill_service

import android.annotation.TargetApi
import android.app.*
import android.app.assist.AssistStructure
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.service.autofill.*
import android.view.*
import android.view.autofill.*

import androidx.annotation.RequiresApi
import mu.KotlinLogging
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import java.util.*


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
        if (parser.fieldIds[AutofillInputType.Password].isNullOrEmpty()){
            val detectedFields = parser.fieldIds.flatMap { it.value }.size
            logger.debug { "got autofillPreferences: ${autofillPreferenceStore.autofillPreferences}"}
            if(!autofillPreferenceStore.autofillPreferences.enableDebug) {
                callback.onSuccess(null)
                return
            }
            useLabel = "Debug: No password fields detected ($detectedFields total)."
        }

        val startIntent = Intent()
        // TODO: Figure this out how to do this without hard coding everything..
        startIntent.setClassName(applicationContext, "design.codeux.authpass.MainActivity")
        startIntent.action = Intent.ACTION_RUN
        //"design.codeux.autofill_service_example.MainActivity")
//        val startIntent = Intent(Intent.ACTION_MAIN).apply {
//                                `package` = applicationContext.packageName
//                    logger.debug { "Creating custom intent." }
//                }
//        startIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startIntent.putExtra("route", "/autofill")
//        startIntent.putParcelableArrayListExtra("autofillIds", ArrayList(parser.autoFillIds))
        val intentSender: IntentSender = PendingIntent.getActivity(
            this,
            0,
            startIntent,
            PendingIntent.FLAG_CANCEL_CURRENT
        ).intentSender
        logger.debug { "startIntent:$startIntent (${startIntent.extras}) - sender: $intentSender" }

        // Build a FillResponse object that requires authentication.
        val fillResponseBuilder: FillResponse.Builder = FillResponse.Builder()
            .setAuthentication(
                parser.autoFillIds.toTypedArray(),
                intentSender,
                RemoteViewsHelper.viewsWithAuth(packageName, useLabel)
            )
        logger.info { "packageName: $packageName" }

        val fillResponse = fillResponseBuilder.build()

        callback.onSuccess(fillResponse)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        logger.info { "onSaveRequest. but not yet implemented." }
        callback.onFailure("Not implemented")
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
    val predicate: AssistStructure.ViewNode.(node: AssistStructure.ViewNode) -> Boolean
)

private fun MutableList<AutofillHeuristic>.heuristic(
    weight: Int,
    predicate: AssistStructure.ViewNode.(node: AssistStructure.ViewNode) -> Boolean
) =
    add(AutofillHeuristic(weight, predicate))


@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.autofillHint(weight: Int, hint: String) =
    heuristic(weight) { autofillHints?.contains(hint) == true }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.idEntry(weight: Int, match: String) =
    heuristic(weight) { idEntry == match }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.htmlAttribute(weight: Int, attr: String, value: String) =
    heuristic(weight) { htmlInfo?.attributes?.firstOrNull { it.first == attr && it.second == value } != null }

@TargetApi(Build.VERSION_CODES.O)
private fun MutableList<AutofillHeuristic>.defaults(hint: String, match: String) {
    autofillHint(900, hint)
    idEntry(800, match)
    heuristic(700) { idEntry?.toLowerCase(Locale.ROOT)?.contains("user") == true }
}

@TargetApi(Build.VERSION_CODES.O)
enum class AutofillInputType(val heuristics: List<AutofillHeuristic>) {
    Email(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_EMAIL_ADDRESS, "email")
        htmlAttribute(400, "type", "email")
        htmlAttribute(300, "name", "email")
        heuristic(200) { hint?.toLowerCase(java.util.Locale.ROOT)?.contains("mail") == true }
    }),
    UserName(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_USERNAME, "user")
        htmlAttribute(400, "name", "user")
        htmlAttribute(400, "name", "username")
    }),
    Password(mutableListOf<AutofillHeuristic>().apply {
        defaults(View.AUTOFILL_HINT_PASSWORD, "password")
        htmlAttribute(400, "type", "password")
        heuristic(500) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) }
        heuristic(499) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) }
        heuristic(498) { inputType.hasFlag(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) }
    }),
}


inline fun Int?.hasFlag(flag: Int) = this != null && flag and this == flag
inline fun Int.withFlag(flag: Int) = this or flag
inline fun Int.minusFlag(flag: Int) = this and flag.inv()


data class MatchedField(val heuristic: AutofillHeuristic, val autofillId: AutofillId)

