package io.legado.app.constant

import org.apache.commons.lang3.time.FastDateFormat

/** Parser-used constants extracted from Legado's application constants. */
object AppConst {
    const val UA_NAME = "User-Agent"
    val dateFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yyyy/MM/dd HH:mm")
    }
}
