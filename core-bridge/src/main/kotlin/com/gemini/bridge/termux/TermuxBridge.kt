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

    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(TERMUX_PKG, 0)
        true
    }.getOrDefault(false)

    suspend fun run(
        command: String,
        workdir: String? = null,
        timeoutMs: Long = 30_000
    ): Result {
        if (!isInstalled()) return Result(false, -1, "", "Termux is not installed")
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
                    appContext.startService(intent)
                } catch (t: Throwable) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    cont.resumeSafely(Result(false, -1, "", t.message ?: "Cannot reach Termux"))
                }
            }
        } ?: Result(false, -1, "", "Timed out after ${timeoutMs}ms")
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
