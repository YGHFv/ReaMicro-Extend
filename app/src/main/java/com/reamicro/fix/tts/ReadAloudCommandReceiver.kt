package com.reamicro.fix.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ReadAloudCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ReadAloudIntents.ACTION_SYNC_PROGRESS) {
            val sent = ReadAloudProgressStore.broadcast(context)
            Log.i(LOG_TAG, "receiver sync progress sent=$sent")
            return
        }
        if (action !in COMMAND_ACTIONS) return
        val serviceIntent = Intent(intent).apply {
            setClass(context, ReadAloudService::class.java)
        }
        runCatching {
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(LOG_TAG, "receiver forward action=$action component=$component")
        }.onFailure {
            Log.i(LOG_TAG, "forward command failed action=$action", it)
        }
    }

    private companion object {
        const val LOG_TAG = "ReaMicroReadAloud"

        val COMMAND_ACTIONS = setOf(
            ReadAloudIntents.ACTION_PREPARE,
            ReadAloudIntents.ACTION_APPEND,
            ReadAloudIntents.ACTION_PLAY,
            ReadAloudIntents.ACTION_PAUSE,
            ReadAloudIntents.ACTION_RESUME,
            ReadAloudIntents.ACTION_STOP,
            ReadAloudIntents.ACTION_PREV,
            ReadAloudIntents.ACTION_NEXT,
        )
    }
}
