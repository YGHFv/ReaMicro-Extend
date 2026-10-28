package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 `.shapes` 与 DexKit 运行期读取的字段形态必须一致。
 *
 * 资产里方法/字段段写的是**完整签名**（`Lowner;->name(params)ret`、`Lowner;->name:type`），
 * 因为 HostDexKit 直接读 `MethodData.descriptor` / `FieldData.descriptor`。
 * 之前这里写成裸描述符（`(I)V`）导致所有 profile 都算出 0/N，
 * 这个用例就是防那次回归：只要形态再被写错，构建就会失败。
 */
class HostShapeAssetTest {
    private fun assetDir(): File {
        val candidates = listOf(
            File("src/main/assets/host-profiles"),
            File("app/src/main/assets/host-profiles"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("host-profiles 资产目录不存在，尝试过：${candidates.joinToString(", ")}")
    }

    private fun parseShapes(file: File): Pair<String, Map<String, String>> {
        var profileId = ""
        val shapes = LinkedHashMap<String, String>()
        file.readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                when {
                    line.startsWith("profile=") -> profileId = line.removePrefix("profile=").trim()
                    line.startsWith("shape=") -> {
                        val body = line.removePrefix("shape=")
                        shapes[body.substringBefore('|')] = body.substringAfter('|')
                    }
                }
            }
        return profileId to shapes
    }

    @Test
    fun `shape assets use dexkit member signature form`() {
        val files = assetDir().listFiles { file -> file.name.endsWith(".shapes") }.orEmpty()
        assertTrue("no .shapes assets found", files.size >= 2)
        for (file in files) {
            val (profileId, shapes) = parseShapes(file)
            assertEquals(
                "profile header must match file name",
                file.nameWithoutExtension,
                profileId,
            )
            assertTrue("$profileId has no shapes", shapes.size > 100)
            for ((logical, shape) in shapes) {
                val sections = shape.split('|')
                assertEquals("$profileId $logical sections", 4, sections.size)
                val (superClass, interfaces, methods, fields) = sections
                if (superClass.isNotEmpty()) {
                    assertTrue("$profileId $logical super", superClass.startsWith("L"))
                }
                interfaces.split(',').filter { it.isNotEmpty() }.forEach {
                    assertTrue("$profileId $logical interface=$it", it.startsWith("L"))
                }
                // 每个成员都必须是完整签名（含 ->）。写成裸描述符是这个资产曾经的真实回归。
                methods.split(',').filter { it.isNotEmpty() }.forEach {
                    assertTrue("$profileId $logical method=$it", it.contains("->"))
                    assertTrue("$profileId $logical method=$it", !it.startsWith("("))
                }
                fields.split(',').filter { it.isNotEmpty() }.forEach {
                    assertTrue("$profileId $logical field=$it", it.contains("->"))
                }
            }
        }
    }

    @Test
    fun `b3 profile and shapes cover the same logical classes`() {
        val profileIds = assetDir().listFiles { file -> file.name.endsWith(".profile") }.orEmpty()
        assertNotNull(profileIds)
        for (profileFile in profileIds) {
            val profileId = profileFile.nameWithoutExtension
            val shapesFile = File(assetDir(), "$profileId.shapes")
            assertTrue("missing $profileId.shapes", shapesFile.isFile)
            val (_, shapes) = parseShapes(shapesFile)
            val mapped = profileFile.readText()
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("class=") }
                .map { it.removePrefix("class=").substringBeforeLast('=').trim() }
                .toSet()
            assertEquals("$profileId logical classes", mapped, shapes.keys)
        }
    }
}
