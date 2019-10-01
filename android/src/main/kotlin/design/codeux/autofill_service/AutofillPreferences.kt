package design.codeux.autofill_service

import android.content.*
import androidx.core.content.edit

class AutofillPreferences private constructor(val prefs: SharedPreferences) {


    companion object {

        private const val SHARED_PREFS_NAME = "design.codeux.autofill.prefs"
        private const val PREF_ENABLE_DEBUG = "enableDebug"

        private val lock = Any()
        private var instance: AutofillPreferences? = null

        fun getInstance(context: Context): AutofillPreferences =
            getInstance(context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE))

        private fun getInstance(prefs: SharedPreferences): AutofillPreferences {
            synchronized(lock) {
                return instance ?: AutofillPreferences(prefs)
            }
        }
    }

    private var _enableDebug: Boolean = false

    init {
        _enableDebug = prefs.getBoolean(PREF_ENABLE_DEBUG, _enableDebug)
    }


    var enableDebug
        get() = _enableDebug
        set(value) {
            _enableDebug = value
            prefs.edit {
                putBoolean(PREF_ENABLE_DEBUG, value)
            }
        }
}