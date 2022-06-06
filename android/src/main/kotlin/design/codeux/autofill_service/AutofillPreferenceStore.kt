package design.codeux.autofill_service

import android.content.*
import androidx.core.content.edit
import mu.KotlinLogging
import org.json.JSONObject

private val logger = KotlinLogging.logger {}

data class AutofillPreferences(
    val enableDebug: Boolean = false
) {

    companion object {
        private const val PREF_JSON_NAME = "AutofillPreferences"
        private const val ENABLE_DEBUG = "enableDebug"

        fun fromPreferences(prefs: SharedPreferences): AutofillPreferences =
            prefs.getString(PREF_JSON_NAME, null)?.let(Companion::fromJsonString)
                ?: AutofillPreferences()

        @Suppress("ComplexRedundantLet")
        private fun fromJsonString(jsonString: String) =
            JSONObject(jsonString).let {
                AutofillPreferences(enableDebug = it.getBoolean(ENABLE_DEBUG))
            }

        fun fromJsonValue(data: Map<String, Any>): AutofillPreferences? =
            AutofillPreferences(enableDebug = (data.get(ENABLE_DEBUG) as? Boolean) ?: false)
    }

    fun saveToPreferences(prefs: SharedPreferences) {
        prefs.edit {
            putString(PREF_JSON_NAME, toJson())
        }
    }

    fun toJsonValue() =
        JSONObject().apply {
            put(ENABLE_DEBUG, enableDebug)
        }

    private fun toJson(): String = toJsonValue().toString()
}

class AutofillPreferenceStore private constructor(private val prefs: SharedPreferences) {


    companion object {

        private const val SHARED_PREFS_NAME = "design.codeux.autofill.prefs"

        private val lock = Any()
        private var instance: AutofillPreferenceStore? = null

        fun getInstance(context: Context): AutofillPreferenceStore =
            instance ?: getInstance(context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE))

        private fun getInstance(prefs: SharedPreferences): AutofillPreferenceStore {
            synchronized(lock) {
                return instance ?: {
                    logger.debug { "Creating new AutofillPreferenceStore." }
                    val ret = AutofillPreferenceStore(prefs)
                    instance = ret
                    ret
                }()
            }
        }

    }

    var autofillPreferences: AutofillPreferences = AutofillPreferences.fromPreferences(prefs)
        set(value) {
            field = value
            value.saveToPreferences(prefs)
        }
}