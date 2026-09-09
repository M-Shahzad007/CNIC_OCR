package com.example.cnic_ocr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog

object UrduKeyboardHelper {

    /**
     * Checks if any currently ENABLED input method on the device declares
     * Urdu ("ur") as a supported subtype locale.
     */
    fun isUrduKeyboardEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList

        android.util.Log.d("UrduKbCheck", "Enabled IMEs: ${enabledMethods.map { it.packageName }}")

        for (imi in enabledMethods) {
            val enabledSubtypes = imm.getEnabledInputMethodSubtypeList(imi, true)
            android.util.Log.d("UrduKbCheck", "${imi.packageName} enabled locales: ${enabledSubtypes.map { it.locale }}")
            for (subtype in enabledSubtypes) {
                if (subtype.locale.startsWith("ur")) {
                    android.util.Log.d("UrduKbCheck", "Urdu FOUND on ${imi.packageName}")
                    return true
                }
            }
        }
        android.util.Log.d("UrduKbCheck", "No Urdu subtype enabled anywhere")
        return false
    }

    /** Opens system Settings where user can enable Urdu subtype for an installed keyboard. */
    fun openInputMethodSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    /** Opens Play Store to Gboard, which supports Urdu and is free. */
    fun openKeyboardDownloadPage(context: Context) {
        val gboardPackage = "com.google.android.inputmethod.latin"
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$gboardPackage"))
            )
        } catch (e: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$gboardPackage"))
            )
        }
    }

    /**
     * Dialog shown when no Urdu keyboard is detected. Offers to send user to
     * settings/Play Store to install one, or fall back to an in-app keyboard.
     */
    fun promptUrduInputChoice(
        context: Context,
        onChooseInstall: () -> Unit,
        onChooseVirtual: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Urdu Keyboard Not Found")
            .setMessage("You don't have an Urdu keyboard enabled. You can enable/install one, or type Urdu using an in-app keyboard instead.")
            .setCancelable(true) // allow back-press / tap-outside to dismiss
            .setPositiveButton("Install / Enable Urdu Keyboard") { _, _ -> onChooseInstall() }
            .setNegativeButton("Use In-App Keyboard") { _, _ -> onChooseVirtual() }
            .setOnCancelListener {
                // User dismissed without choosing (back press / tap outside) ->
                // default to the virtual keyboard rather than leaving the field
                // with no way to type Urdu at all.
                onChooseVirtual()
            }
            .show()
    }
}