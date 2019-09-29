package design.codeux.autofill_service

import android.annotation.TargetApi
import android.app.*
import android.app.assist.AssistStructure
import android.content.*
import android.os.*
import android.service.autofill.*
import android.view.autofill.*
import android.widget.RemoteViews
import androidx.fragment.app.FragmentActivity
import kotlinx.android.synthetic.main.activity_dummy_test.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}


class DummyTestActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dummy_test)

        button.setOnClickListener {
            doStuff()
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun doStuff() {
        val structureParcel =
            intent?.extras?.getParcelable<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
                ?: intent?.extras?.getParcelable<AssistStructure>(
                    AutofillManager.EXTRA_ASSIST_STRUCTURE
                )
        if (structureParcel == null) {
            logger.info { "No structure available." }
            return
        }

        val structure = AssistStructureParser(structureParcel)

        val autofillIds =
            (intent)?.extras?.getParcelableArrayList<AutofillId>(
                "autofillIds"
            )
        logger.debug { "structure: $structure /// autofillIds: $autofillIds" }
        logger.info { "packageName: ${packageName}" }

        val remoteViews = {
            RemoteViewsHelper.viewsWithNoAuth(
                packageName, "Fill Me"
            )
        }
        structure.fieldIds.values.forEach { it.sortByDescending { it.heuristic.weight } }


        val startIntent = Intent(applicationContext, DummyTestActivity::class.java)
//        startIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
//        startIntent.putExtra("route", "/autofill")
        startIntent.addCategory("abc")
//        startIntent.putParcelableArrayListExtra("autofillIds", ArrayList(parser.autoFillIds))
        val intentSender: IntentSender = PendingIntent.getActivity(
            this,
            1,
            startIntent,
            PendingIntent.FLAG_CANCEL_CURRENT
        ).intentSender
        logger.debug { "startIntent:$startIntent (${startIntent.extras}) - sender: $intentSender" }


        val frb = FillResponse.Builder()
//            .setAuthentication(
//                structure.autoFillIds.toTypedArray(),
//                null,
//                null
//            )
        listOf(100, 101, 102, 103, 200, 201, 203, 204).forEach { c ->
            val dsb = Dataset.Builder(remoteViews())
                dsb.setId("test $c")
                if (c == 203) {
                    dsb.setAuthentication(intentSender)
                }
                structure.fieldIds[AutofillInputType.Email]?.forEach { field ->
                    logger.debug("Adding data set for email ${field.autofillId}")
                    dsb.setValue(
                        field.autofillId,
                        AutofillValue.forText("some email"),
                        RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                            setTextViewText(android.R.id.text1, "$c email for my_username")
                        })
                }
                structure.fieldIds[AutofillInputType.Password]?.forEach { field ->
                    logger.debug("Adding data set for password ${field.autofillId}")
                    dsb.setValue(
                        field.autofillId,
                        AutofillValue.forText("password"),
                        RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                            setTextViewText(android.R.id.text1, "$c password for my_username")
                        })
                }
                structure.fieldIds[AutofillInputType.UserName]?.forEach { field ->
                    logger.debug("Adding data set for username ${field.autofillId}")
                    dsb.setValue(
                        field.autofillId,
                        AutofillValue.forText("username"),
                        RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                            setTextViewText(android.R.id.text1, "$c username for my_username")
                        })
                }
                structure.allNodes.forEach { node ->
                    if (node.isFocused && node.autofillId != null) {
                        logger.debug("Setting focus node. ${node.autofillId}")
                        dsb.setValue(
                            node.autofillId!!,
                            AutofillValue.forText("focus"),
                            RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                                setTextViewText(android.R.id.text1, "$c focus for my_username")
                            })

                    }
                }
            frb.addDataset(dsb.build())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            frb.setFooter(RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "some footer")
            })
        }
        val replyIntent = Intent()
        replyIntent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, frb.build())

        setResult(Activity.RESULT_OK, replyIntent)
        finish()
    }
}
