package com.reamicro.fix.hook

/** Native S3 enables a filled form; detailed endpoint/auth validation belongs to the submit action. */
internal object WebDavConfigurationState {
    fun ready(address: String): Boolean = address.isNotBlank()
    fun canSubmit(address: String, busy: Boolean): Boolean = !busy && ready(address)
}
