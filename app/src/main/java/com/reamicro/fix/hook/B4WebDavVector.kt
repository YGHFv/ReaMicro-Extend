package com.reamicro.fix.hook

import androidx.core.graphics.PathParser
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.hook.webdav.*

/** B4 删除了旧 VectorKt 门面；通过与原生图标相同的构造器和路径节点生成矢量图。 */
internal class B4WebDavVector(private val drive: WebDavDriveHook) {
    fun build(): Any = buildVector(
        "Colored.WebDAV",
        listOf(
            WEBDAV_ICON_BODY_PATH to (0xFF4BAFA7L to 1f),
            WEBDAV_ICON_CLOUD_PATH to (0xFFFFFFFFL to 1f),
            WEBDAV_ICON_SLOT_PATH to (0xFFFFFFFFL to 0.84f),
        ),
    )

    /** B4 混淆掉了 material icons 门面，用原生矢量构造器复刻「更新」刷新图标。 */
    fun buildRefresh(): Any = buildVector(
        "Colored.Refresh",
        listOf(REFRESH_ICON_PATH to (0xFFFFFFFFL to 1f)),
    )

    private fun buildVector(name: String, paths: List<Pair<String, Pair<Long, Float>>>): Any {
        check(HostSymbols.profileId == "2.3.3-b4")
        val float = Float::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        val builderType = drive.cls("ReaMicroHost.VectorBuilder")
        val pathType = drive.cls("ReaMicroHost.VectorPathBuilder")
        val brushType = drive.cls("ReaMicroHost.VectorBrush")
        val builder = builderType.getDeclaredConstructor(
            String::class.java, float, float, float, float, Long::class.javaPrimitiveType,
            int, Boolean::class.javaPrimitiveType, int,
        ).newInstance(name, 24f, 24f, 24f, 24f, 0L, 0, false, 224)
        val add = builderType.getDeclaredMethod(
            "d", builderType, ArrayList::class.java, int, brushType, float, float, float, int, float,
        )
        val color = drive.cls("ReaMicroHost.VectorColor").getDeclaredMethod("d", Long::class.javaPrimitiveType)
        val brush = brushType.getDeclaredConstructor(Long::class.javaPrimitiveType)
        val commands = mapOf(
            'M' to ("k" to 2), 'm' to ("l" to 2), 'L' to ("i" to 2), 'l' to ("j" to 2),
            'H' to ("g" to 1), 'h' to ("h" to 1), 'V' to ("p" to 1), 'v' to ("q" to 1),
            'C' to ("e" to 6), 'c' to ("f" to 6), 'S' to ("m" to 4), 's' to ("n" to 4),
        )
        fun addPath(data: String, argb: Long, alpha: Float) {
            val path = pathType.getDeclaredConstructor(Byte::class.javaPrimitiveType).newInstance(3.toByte())
            PathParser.createNodesFromPathData(data).forEach { node ->
                if (node.type == 'z' || node.type == 'Z') {
                    check(node.params.isEmpty())
                    pathType.getDeclaredMethod("d").invoke(path)
                } else {
                    val (name, arity) = requireNotNull(commands[node.type]) { "Unverified vector path command" }
                    check(node.params.isNotEmpty() && node.params.size % arity == 0)
                    node.params.toList().chunked(arity).forEachIndexed { index, args ->
                        val actual = if (index > 0 && node.type == 'M') "i"
                            else if (index > 0 && node.type == 'm') "j" else name
                        pathType.getDeclaredMethod(actual, *Array(arity) { float })
                            .invoke(path, *args.toTypedArray())
                    }
                }
            }
            val nodes = pathType.getDeclaredField("a").get(path)
            add.invoke(null, builder, nodes, 0, brush.newInstance(color.invoke(null, argb)),
                alpha, 1f, 0f, 0, 4f)
        }
        paths.forEach { (data, paint) -> addPath(data, paint.first, paint.second) }
        return requireNotNull(builderType.getDeclaredMethod("e").invoke(builder))
    }
}
