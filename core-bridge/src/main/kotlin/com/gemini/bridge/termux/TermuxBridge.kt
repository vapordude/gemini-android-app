package com.gemini.bridge.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Sends commands to Termux via the `com.termux.RUN_COMMAND` service. Termux
 * returns stdout / stderr / exit via a PendingIntent broadcast we register a
 * one-shot receiver for.
 *
 * The user must have Termux installed, enabled `allow-external-apps = true`
 * in `~/.termux/termux.properties`, and granted the RUN_COMMAND permission.
 */
class TermuxBridge(private val appContext: Context) {

    data class Result(val ok: Boolean, val exitCode: Int, val stdout: String, val stderr: String)

    @Volatile private var autoSetupAttempted = false

    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(TERMUX_PKG, 0)
        true
    }.getOrDefault(false)

    suspend fun run(
        command: String,
        workdir: String? = null,
        timeoutMs: Long = 12_000
    ): Result {
        if (!isInstalled()) return Result(
            false, -1, "",
            "Termux is not installed. Install it from F-Droid then enable RUN_COMMAND."
        )
        val first = dispatch(command, workdir, timeoutMs)
        if (workdir == null || !isWorkdirDenied(first)) return first

        // Termux refused the WORKDIR because it lacks shared-storage access.
        // First denial: fire `termux-setup-storage` once — Termux pops the
        // Android permission dialog, user taps Allow, and every later command
        // lands in the workspace. This call runs the current command from
        // $HOME so the user isn't blocked, and annotates the hint.
        if (!autoSetupAttempted) {
            autoSetupAttempted = true
            // Fire-and-forget: Termux opens the permission dialog on its own
            // thread; the RUN_COMMAND broadcast returns before the user taps.
            dispatch("termux-setup-storage", null, timeoutMs)
            val fallback = dispatch(command, null, timeoutMs)
            val hint = "note: Termux couldn't read $workdir yet. A storage " +
                "permission dialog was just opened in Termux — tap Allow and " +
                "future commands will land in the workspace. This command ran " +
                "from Termux's \$HOME in the meantime."
            return fallback.copy(stderr = mergeErr(fallback.stderr, hint))
        }

        // Already tried auto-setup earlier — user likely declined the dialog.
        // Keep working by falling back to $HOME and surface the manual fix.
        val fallback = dispatch(command, null, timeoutMs)
        val hint = "note: Termux still can't read $workdir. Open Termux and " +
            "run `termux-setup-storage`, then accept the Android permission " +
            "dialog. Until then, prefer the workspace file tools over shell " +
            "commands for file-oriented work."
        return fallback.copy(stderr = mergeErr(fallback.stderr, hint))
    }

    private fun mergeErr(existing: String, hint: String): String =
        listOfNotNull(existing.ifBlank { null }, hint).joinToString("\n").trimEnd()

    private suspend fun dispatch(
        command: String,
        workdir: String?,
        timeoutMs: Long
    ): Result {
        val resultAction = ACTION_RESULT_PREFIX + UUID.randomUUID().toString()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        cont.resumeSafely(parseResult(intent.extras))
                    }
                }
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(resultAction),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

                val pending = PendingIntent.getBroadcast(
                    appContext,
                    resultAction.hashCode(),
                    Intent(resultAction).setPackage(appContext.packageName),
                    pendingFlags()
                )
                val intent = Intent().apply {
                    component = ComponentName(TERMUX_PKG, TERMUX_SERVICE)
                    action = "com.termux.RUN_COMMAND"
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0,1,0,1")
                    putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
                    if (workdir != null) putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(intent)
                    } else {
                        appContext.startService(intent)
                    }
                } catch (t: Throwable) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    cont.resumeSafely(
                        Result(
                            false, -1, "",
                            "Cannot reach Termux: ${t.message ?: "service refused"}. " +
                                "Open Termux once, then in it run:\n" +
                                "  mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                                "  termux-reload-settings\n" +
                                "Also grant the RUN_COMMAND permission in Android Settings."
                        )
                    )
                }
            }
        } ?: Result(
            false, -1, "",
            "Timed out after ${timeoutMs}ms — Termux did not reply. Check that " +
                "(1) the RUN_COMMAND permission is granted to this app and " +
                "(2) ~/.termux/termux.properties contains `allow-external-apps=true` " +
                "(then run `termux-reload-settings` inside Termux). Open Termux once " +
                "manually before retrying."
        )
    }

    // Termux's RunCommandService rejects an unreadable WORKDIR with a
    // FileUtils error that looks like:
    //     Error Code: 401
    //     Error Message (FileUtils Error):
    //     The working directory file at path "…" is not readable. Permission Denied.
    private fun isWorkdirDenied(r: Result): Boolean {
        if (r.ok) return false
        val blob = (r.stderr + '\n' + r.stdout)
        return blob.contains("working directory", ignoreCase = true) &&
            (blob.contains("not readable", ignoreCase = true) ||
                blob.contains("Permission Denied", ignoreCase = true))
    }

    private fun parseResult(extras: Bundle?): Result {
        val bundle = extras?.getBundle("result") ?: return Result(false, -1, "", "No Termux result")
        val stdout = bundle.getString("stdout").orEmpty().trimEnd()
        val stderr = bundle.getString("stderr").orEmpty().trimEnd()
        val exit = bundle.getInt("exitCode", -1)
        val err = bundle.getString("errmsg")
        val combinedErr = listOfNotNull(stderr.ifBlank { null }, err).joinToString("\n").trimEnd()
        return Result(exit == 0, exit, stdout, combinedErr)
    }

    private fun pendingFlags(): Int {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.FLAG_UPDATE_CURRENT or mutable
    }

    private fun <T> CancellableContinuation<T>.resumeSafely(value: T) {
        if (isActive) resume(value)
    }

    companion object {
        private const val TERMUX_PKG = "com.termux"
        private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RESULT_PREFIX = "com.gemini.app.TERMUX_RESULT_"
    }
}
