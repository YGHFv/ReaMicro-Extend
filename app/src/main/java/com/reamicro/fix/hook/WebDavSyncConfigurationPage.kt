package com.reamicro.fix.hook

import android.app.Activity
import android.view.View

/** Draft data stays local while the current host's S3 composition renders the entire editor. */
internal class WebDavSyncConfigurationPage(
    private val activity: Activity,
    initial: Credentials,
    private val onSave: (Credentials) -> Unit,
    private val onClosed: () -> Unit,
    private val native: HostWebDavConfigurationUi,
    private val isCurrentOwner: () -> Boolean,
) {
    data class Credentials(val address: String, val username: String, val password: String) {
        override fun toString(): String = "WebDavCredentials(redacted)"
    }
    private val ui = native.ui
    private val host = native.host
    val busy = requireNotNull(ui.call("state", false))
    private val config = requireNotNull(ui.call("state", configuration(initial)))
    private val problem = requireNotNull(ui.call("state", null))
    private val account = requireNotNull(ui.call("state", ""))
    val body: Any = host.constructor("configurationBody").newInstance(true, host.configurationPreset, account, config, problem)
    private var closed = false
    private var canceled = false
    private var sentinel: View? = null
    private var back: (() -> Unit)? = null
    private var releaseSecure: (() -> Unit)? = null

    private fun configuration(draft: Credentials): Any = host.newConfig(draft.address, "", "auto", draft.username, draft.password, "reamicro")
    private fun draft(): Credentials {
        val value = requireNotNull(ui.stateValue(config))
        return Credentials(host.config(value, "endpoint") as String, host.config(value, "accessKey") as String, host.config(value, "secretKey") as String)
    }
    fun readOnly(): Boolean = canceled || closed || !isCurrentOwner() || ui.stateValue(busy) == true
    fun canSave(): Boolean = !readOnly() && WebDavConfigurationState.ready(draft().address)
    fun submit() { if (canSave()) onSave(draft()) }
    fun change(key: String, value: String) {
        if (readOnly()) return
        val old = draft()
        val next = when (key) {
            "address" -> old.copy(address = value)
            "username" -> old.copy(username = value)
            "password" -> old.copy(password = value)
            else -> return
        }
        ui.setState(config, configuration(next)); ui.setState(problem, null)
    }
    fun verifying() { if (!closed && !canceled) { ui.setState(busy, true); ui.setState(problem, null) } }
    fun error(message: String) { if (!closed && !canceled) { ui.setState(busy, false); ui.setState(problem, message) } }
    fun render(composer: Any) { if (!closed) native.render(this, composer) }

    /** A zero-size native AndroidView owns the destination release boundary, including exit motion. */
    fun createEmbeddedView(onBack: () -> Unit): View {
        check(!closed && !canceled && !activity.isFinishing && !activity.isDestroyed) { "同步配置页已关闭" }
        sentinel?.let { return it }
        back = onBack
        releaseSecure = HostSecurePageLease.acquire(activity.window)
        return View(activity).also { sentinel = it }
    }
    fun cancelPending() {
        if (canceled) return
        canceled = true
        ui.setState(busy, true)
        onClosed()
    }
    fun dismiss() {
        cancelPending()
        back?.invoke() ?: closeEmbedded()
    }
    fun closeEmbedded() {
        if (closed) return
        closed = true
        try { cancelPending() } finally {
            back = null; sentinel = null
            try {
                ui.setState(config, configuration(Credentials("", "", "")))
                ui.setState(problem, null)
                native.release(this)
            } finally { releaseSecure?.invoke(); releaseSecure = null }
        }
    }
}
