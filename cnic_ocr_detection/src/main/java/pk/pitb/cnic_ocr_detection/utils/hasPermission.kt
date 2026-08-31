package pk.pitb.cnic_ocr_detection.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import pk.pitb.cnic_ocr_detection.R

fun Activity.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    startActivity(intent)
}

fun Activity.showPermissionDialog(
    title: String,
    body: String,
    trueBtnText: String = "Proceed",
    falseBtnText: String? = null,
    permissions: Array<String>,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    val dialogView = LayoutInflater.from(this).inflate(R.layout.permission_dialog, null)
    val titleView: TextView = dialogView.findViewById(R.id.tv_dialog_title)
    val bodyView: TextView = dialogView.findViewById(R.id.tv_dialog_body)
    val cancelButton: TextView = dialogView.findViewById(R.id.tv_cancel)
    val proceedButton: TextView = dialogView.findViewById(R.id.tv_proceed)

    titleView.text = title
    bodyView.text = body
    falseBtnText?.let { cancelButton.text = it }
    proceedButton.text = trueBtnText

    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(false)
        .create()

    cancelButton.setOnClickListener {
        dialog.dismiss()
        onPermissionDenied()
    }

    proceedButton.setOnClickListener {
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        dialog.dismiss()
    }

    dialog.show()
    // Optional: Adjust the width of the dialog
    val layoutParams = dialog.window?.attributes
    layoutParams?.width = (resources.displayMetrics.widthPixels * 0.9).toInt() // 90% of screen width
    dialog.window?.attributes = layoutParams
    dialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
}

fun Activity.handlePermissionResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray, onPermissionGranted: () -> Unit, onPermissionDenied: () -> Unit) {
    if (requestCode == PERMISSION_REQUEST_CODE) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onPermissionGranted()
        } else {
            onPermissionDenied()
        }
    }
}

const val PERMISSION_REQUEST_CODE = 1234 // Choose a unique request code

