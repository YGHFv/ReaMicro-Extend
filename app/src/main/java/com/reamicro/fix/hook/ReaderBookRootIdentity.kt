package com.reamicro.fix.hook

import java.io.File

/** Android 的 /data/user/0 与 /data/data 可指向同一目录，身份比较必须同样规范化。 */
internal fun sameReaderBookRoot(first: File?, second: File): Boolean =
    first != null && runCatching { first.canonicalFile == second.canonicalFile }.getOrDefault(false)
