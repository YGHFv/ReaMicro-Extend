package com.reamicro.fix.hook

/** BottomWebViewDialog.buildSpliceHtml: preload runs before the page's own head scripts. */
internal object BrowserPageContent {
    fun splice(html: String, preload: String?, basic: String, injection: String): String {
        if (preload.isNullOrEmpty()) return html
        val script = """
            <script type="text/javascript">
                $basic
                (() => {
                    $injection
                    $preload
                })();
            </script>
        """.trimIndent()
        val head = html.indexOf("<head", ignoreCase = true)
        val end = if (head >= 0) html.indexOf('>', startIndex = head) else -1
        return if (end >= 0) html.substring(0, end + 1) + script + html.substring(end + 1)
            else script + html
    }
}
