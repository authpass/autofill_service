package design.codeux.autofill_service

import android.annotation.TargetApi
import android.app.assist.AssistStructure
import android.os.*
import android.view.*
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi
import mu.KotlinLogging
import org.json.JSONObject

private val logger = KotlinLogging.logger {}

data class WebDomain (val scheme: String?, val domain: String) {
    fun toMap() = mapOf(
        SCHEME to scheme,
        DOMAIN to domain,
    )

    companion object {
        private const val SCHEME = "scheme"
        private const val DOMAIN = "domain"

        fun fromJson(obj: JSONObject) =
            WebDomain(
                scheme = obj.optString(SCHEME),
                domain = obj.optString(DOMAIN, ""),
            )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class AssistStructureParser(structure: AssistStructure) {

    val autoFillIds = mutableListOf<AutofillId>()
    val allNodes = mutableListOf<AssistStructure.ViewNode>()

    var packageName = HashSet<String>()
    var webDomain = HashSet<WebDomain>()

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

        viewNode.autofillId?.let { autoFillIds.add(it) }
        if (viewNode.autofillHints?.isNotEmpty() == true) {
            // If the client app provides autofill hints, you can obtain them using:
            logger.debug { "$depth     autofillHints: ${viewNode.autofillHints?.contentToString()}" }
        } else {
            // Or use your own heuristics to describe the contents of a view
            // using methods such as getText() or getHint().
            logger.debug { "$depth     viewNode no hints, text:${viewNode.text} and hint:${viewNode.hint} and inputType:${viewNode.inputType}" }
        }

       viewNode.idPackage?.let { idPackage ->
            packageName.add(idPackage)
        }
        viewNode.webDomain?.let { myWebDomain ->
            webDomain.add(
                WebDomain(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        viewNode.webScheme
                    } else {
                        null
                    }, myWebDomain
                )
            )
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