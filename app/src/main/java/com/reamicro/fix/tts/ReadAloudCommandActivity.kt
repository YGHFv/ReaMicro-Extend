package com.reamicro.fix.tts

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

class ReadAloudCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardCommand(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardCommand(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun forwardCommand(intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in COMMAND_ACTIONS) return
        val serviceIntent = Intent(intent).apply {
            setClass(this@ReadAloudCommandActivity, ReadAloudService::class.java)
        }
        runCatching {
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(LOG_TAG, "activity forward action=$action component=$component")
        }.onFailure {
            Log.i(LOG_TAG, "activity forward failed action=$action", it)
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
