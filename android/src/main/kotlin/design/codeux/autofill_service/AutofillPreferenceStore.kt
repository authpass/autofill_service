package design.codeux.autofill_service

import android.content.*
import androidx.core.content.edit
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AutofillPreferences(
    val enableDebug: Boolean
) {

    companion object {
        private const val PREF_ENABLE_DEBUG = "enableDebug"

        fun fromPreferences(prefs: SharedPreferences) : AutofillPreferences {
            return AutofillPreferences(
                enableDebug = prefs.getBoolean(PREF_ENABLE_DEBUG, false)
            )
        }
    }

    fun saveToPreferences(prefs: SharedPreferences) {
        prefs.edit {
            putBoolean(PREF_ENABLE_DEBUG, enableDebug)
        }
    }
}

class AutofillPreferenceStore private constructor(val prefs: SharedPreferences) {


    companion object {

        private const val SHARED_PREFS_NAME = "design.codeux.autofill.prefs"

        private val lock = Any()
        private var instance: AutofillPreferenceStore? = null

        fun getInstance(context: Context): AutofillPreferenceStore =
            getInstance(context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE))

        private fun getInstance(prefs: SharedPreferences): AutofillPreferenceStore {
            synchronized(lock) {
                return instance ?: {
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