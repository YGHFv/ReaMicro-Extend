package com.reamicro.fix.core

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private val hostDexEntryName = Regex("classes(?:[0-9]+)?\\.dex")

/** The exact classes.dex, classes2.dex, ... sequence opened by DexKit 2.3.0 AddZipPath. */
internal fun ZipFile.hostDexEntries(requireDex: Boolean = true): List<ZipEntry> {
    val candidates = entries().asSequence().filter {
        !it.isDirectory && hostDexEntryName.matches(it.name)
    }.toList()
    val byName = candidates.associateBy { it.name }
    require(byName.size == candidates.size) { "Host APK contains duplicate DEX entries" }
    require(!requireDex || byName.isNotEmpty()) { "Host APK contains no DEX" }
    return (1..byName.size).map { index ->
        val name = if (index == 1) "classes.dex" else "classes$index.dex"
        requireNotNull(byName[name]) { "Host APK DEX sequence is not contiguous: $name" }
    }
}
