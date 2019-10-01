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

        val detectedFields = parser.fieldIds.flatMap { it.value }.size
        var useLabel = unlockLabel
        if (detectedFields == 0){
            if(!autofillPreferenceStore.autofillPreferences.enableDebug) {
                callback.onSuccess(null)
                return
            }
            useLabel = "Debug: No autfill detected."
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

@RequiresApi(Build.VERSION_CODES.O)
class AssistStructureParser(structure: AssistStructure) {

    val autoFillIds = mutableListOf<AutofillId>()
    val allNodes = mutableListOf<AssistStructure.ViewNode>()

    var packageName: String? = null
    var webDomain: String? = null

    val fieldIds =
        mutableMapOf<AutofillInputType, MutableList<MatchedField>>()

    init {
        traverseStructure(structure)
    }

    private fun traverseStructure(structure: AssistStructure) {
        val windowNodes: List<AssistStructure.WindowNode> =
            structure.run {
                (0 until windowNodeCount).map { getWindowNodeAt(it) }
            }

        logger.debug { "Traversing windowNodes $windowNodes" }
        windowNodes.forEach { windowNode: AssistStructure.WindowNode ->
            windowNode.rootViewNode?.let { traverseNode(it, "") }
        }
    }

    private fun Any.debugToString(): String =
        when (this) {
            is Array<*> -> this.contentDeepToString()
            is Bundle -> keySet().map {
                it to get(it)?.toString()
            }.toString()
            is ViewStructure.HtmlInfo -> "HtmlInfo{<$tag ${attributes?.joinToString(" ") { "${it.first}=\"${it.second}\"" }}>}"
            else -> this.toString()
        }

    @TargetApi(Build.VERSION_CODES.O)
    private fun traverseNode(viewNode: AssistStructure.ViewNode, depth: String) {
        allNodes.add(viewNode)
//        logger.debug { "We got autofillId: ${viewNode.autofillId} autofillOptions:${viewNode.autofillOptions} autofillType:${viewNode.autofillType} autofillValue:${viewNode.autofillValue} " }
        val debug =
            (listOf(
                viewNode::getId,
                viewNode::getAutofillId,
                viewNode::getClassName,
                viewNode::getWebDomain,
                viewNode::getAutofillId,
                viewNode::getAutofillHints,
                viewNode::getAutofillOptions,
                viewNode::getAutofillType,
                viewNode::getAutofillValue,
                viewNode::getText,
                viewNode::getHint,
                viewNode::getIdEntry,
                viewNode::getIdPackage,
                viewNode::getIdType,
                viewNode::getInputType,
                viewNode::getContentDescription,
                viewNode::getHtmlInfo,
                viewNode::getExtras
            ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                listOf(
                    viewNode::getWebScheme,
                    viewNode::getTextIdEntry,
                    viewNode::getImportantForAutofill
                )
            } else {
                emptyList()
            })
                .map { it.name.replaceFirst("get", "") to it.invoke()?.debugToString() }
//        logger.debug { "$depth ` ViewNode: $debug ---- ${debug.toList()}" }
        logger.debug { "$depth ` ViewNode: ${debug.filter { it.second != null }.toList()}" }
        logger.debug { "$depth     We got autofillId: ${viewNode.autofillId} autofillOptions:${viewNode.autofillOptions} autofillType:${viewNode.autofillType} autofillValue:${viewNode.autofillValue} " }
//        logger.debug { "$depth ` We got node: ${viewNode.toStringReflective()}" }

        viewNode.autofillId.let(autoFillIds::add)
        if (viewNode.autofillHints?.isNotEmpty() == true) {
            // If the client app provides autofill hints, you can obtain them using:
            logger.debug { "$depth     autofillHints: ${viewNode.autofillHints?.contentToString()}" }
        } else {
            // Or use your own heuristics to describe the contents of a view
            // using methods such as getText() or getHint().
            logger.debug { "$depth     viewNode no hints, text:${viewNode.text} and hint:${viewNode.hint} and inputType:${viewNode.inputType}" }
        }

        if (viewNode.idPackage != null) {
            packageName = viewNode.idPackage
        }
        if (viewNode.webDomain != null) {
            webDomain = viewNode.webDomain
        }
        viewNode.autofillId?.let { autofillId ->
            AutofillInputType.values().forEach { type ->
                fieldIds.getOrPut(type) { mutableListOf() }.addAll(
                    type.heuristics
                        .filter { viewNode.autofillType != View.AUTOFILL_TYPE_NONE }
                        .filter { it.predicate(viewNode, viewNode) }
                        .map { MatchedField(it, autofillId) }
                )
            }
        }

        val children: List<AssistStructure.ViewNode>? =
            viewNode.run {
                (0 until childCount).map { getChildAt(it) }
            }

        children?.forEach { childNode: AssistStructure.ViewNode ->
            traverseNode(childNode, "    ")
        }
    }

    override fun toString(): String {
        return "AssistStructureParser(autoFillIds=$autoFillIds, packageName=$packageName, webDomain=$webDomain, fieldIds=$fieldIds)"
    }


}
