package design.codeux.autofill_service

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Chromium based browsers no longer support the autofill compatibility mode. Instead they
 * forward form data to the autofill framework natively - but only after the user enabled
 * "Autofill using another service" in the browser settings (which is off by default).
 *
 * Chrome exposes the state of that setting through a content provider, so we can tell the
 * user about it instead of silently doing nothing.
 *
 * https://chromium.googlesource.com/chromium/src/+/HEAD/components/android_autofill/README.md
 */
data class BrowserAutofillStatus(
    val packageName: String,
    val label: String,
    val thirdPartyModeEnabled: Boolean
) {
    fun toMap() = mapOf(
        PACKAGE_NAME to packageName,
        LABEL to label,
        THIRD_PARTY_MODE_ENABLED to thirdPartyModeEnabled,
    )

    companion object {
        private const val PACKAGE_NAME = "packageName"
        private const val LABEL = "label"
        private const val THIRD_PARTY_MODE_ENABLED = "thirdPartyModeEnabled"
    }
}

object BrowserAutofill {

    /**
     * Browsers which are known to expose [AUTOFILL_THIRD_PARTY_MODE_AUTHORITY_SUFFIX].
     * Other chromium forks simply won't answer the query and are skipped.
     */
    private val KNOWN_BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
    )

    private const val AUTOFILL_THIRD_PARTY_MODE_AUTHORITY_SUFFIX =
        ".AutofillThirdPartyModeContentProvider"
    private const val AUTOFILL_THIRD_PARTY_MODE_PATH = "autofill_third_party_mode"
    private const val AUTOFILL_THIRD_PARTY_MODE_COLUMN = "autofill_third_party_state"

    /**
     * Queries all known browsers for the state of their "Autofill using another service"
     * setting. Browsers which are not installed, or which are too old to expose the
     * content provider, are not part of the result.
     */
    fun queryStatus(context: Context): List<BrowserAutofillStatus> =
        KNOWN_BROWSER_PACKAGES.mapNotNull { packageName ->
            queryThirdPartyMode(context, packageName)?.let { thirdPartyModeEnabled ->
                BrowserAutofillStatus(
                    packageName = packageName,
                    label = loadLabel(context, packageName) ?: packageName,
                    thirdPartyModeEnabled = thirdPartyModeEnabled,
                )
            }
        }

    /**
     * Intent which opens the settings of the given browser, where the user can enable
     * "Autofill using another service".
     */
    fun settingsIntent(packageName: String): Intent =
        Intent(Intent.ACTION_APPLICATION_PREFERENCES).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_APP_BROWSER)
            addCategory(Intent.CATEGORY_PREFERENCE)
            setPackage(packageName)
        }

    private fun queryThirdPartyMode(context: Context, packageName: String): Boolean? {
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(packageName + AUTOFILL_THIRD_PARTY_MODE_AUTHORITY_SUFFIX)
            .path(AUTOFILL_THIRD_PARTY_MODE_PATH)
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val index = cursor.getColumnIndex(AUTOFILL_THIRD_PARTY_MODE_COLUMN)
                if (index < 0) {
                    return@use null
                }
                cursor.getInt(index) == 1
            }
        } catch (e: Exception) {
            // the provider does not exist (old browser version) or is not accessible.
            logger.debug(e) { "Unable to query third party autofill mode for $packageName" }
            null
        }
    }

    private fun loadLabel(context: Context, packageName: String): String? =
        try {
            context.packageManager.run {
                getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            logger.debug(e) { "Unable to load label for $packageName" }
            null
        }
}
