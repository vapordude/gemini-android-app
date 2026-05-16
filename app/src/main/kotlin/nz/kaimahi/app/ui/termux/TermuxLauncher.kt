package nz.kaimahi.app.ui.termux

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_INSTALL_URL = "https://f-droid.org/packages/com.termux/"
private const val GEMINI_CLI_LOGIN_COMMAND = "gemini login"

fun startGeminiCliLoginInTermux(context: Context) {
    copyToClipboard(context, "gemini-cli-login", GEMINI_CLI_LOGIN_COMMAND)
    Toast.makeText(
        context,
        "Copied: gemini login. In Termux: long-press → Paste → Enter.",
        Toast.LENGTH_LONG
    ).show()
    openTermux(context)
}

fun openTermux(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
    if (launch != null) {
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    openUrl(context, TERMUX_INSTALL_URL)
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
    }
}
