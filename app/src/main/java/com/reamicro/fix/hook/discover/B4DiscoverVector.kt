package com.reamicro.fix.hook.discover

import androidx.core.graphics.PathParser
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass

/** 按 B4 原生图标的九参构造器和路径协议还原旧图稿，不借用模块 Compose 对象。 */
internal object B4DiscoverVector {
    fun build(
        loader: ClassLoader,
        name: String,
        layers: List<Pair<Long, String>>,
        size: Float,
        viewport: Float,
        evenOddFill: Boolean,
    ): Any {
        check(HostSymbols.profileId == "2.3.3-b4")
        val float = Float::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        val builderType = loader.hostClass("ReaMicroHost.VectorBuilder")
        val pathType = loader.hostClass("ReaMicroHost.VectorPathBuilder")
        val brushType = loader.hostClass("ReaMicroHost.VectorBrush")
        val builder = builderType.getDeclaredConstructor(
            String::class.java, float, float, float, float, Long::class.javaPrimitiveType,
            int, Boolean::class.javaPrimitiveType, int,
        ).newInstance(name, size, size, viewport, viewport, 0L, 0, false, 224)
        val add = builderType.getDeclaredMethod(
            "d", builderType, ArrayList::class.java, int, brushType, float, float, float, int, float,
        )
        val color = loader.hostClass("ReaMicroHost.VectorColor")
            .getDeclaredMethod("d", Long::class.javaPrimitiveType)
        val brush = brushType.getDeclaredConstructor(Long::class.javaPrimitiveType)
        val commands = mapOf(
            'M' to ("k" to 2), 'm' to ("l" to 2), 'L' to ("i" to 2), 'l' to ("j" to 2),
            'H' to ("g" to 1), 'h' to ("h" to 1), 'V' to ("p" to 1), 'v' to ("q" to 1),
            'C' to ("e" to 6), 'c' to ("f" to 6), 'S' to ("m" to 4), 's' to ("n" to 4),
        )
        layers.forEach { (argb, data) ->
            val path = pathType.getDeclaredConstructor(Byte::class.javaPrimitiveType).newInstance(3.toByte())
            PathParser.createNodesFromPathData(data).forEach { node ->
                when (node.type) {
                    'Z', 'z' -> {
                        check(node.params.isEmpty())
                        pathType.getDeclaredMethod("d").invoke(path)
                    }
                    'A', 'a' -> {
                        check(node.params.size % 7 == 0)
                        node.params.toList().chunked(7).forEach { values ->
                            // 顶栏三点图稿只用零旋转圆弧；宿主已内联旋转槽，拒绝默默丢参数。
                            check(values[2] == 0f && values[3] in listOf(0f, 1f) && values[4] in listOf(0f, 1f))
                            pathType.getDeclaredMethod(
                                if (node.type == 'A') "b" else "c", float, float,
                                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, float, float,
                            ).invoke(path, values[0], values[1], values[3] != 0f, values[4] != 0f, values[5], values[6])
                        }
                    }
                    else -> {
                        val (method, arity) = requireNotNull(commands[node.type]) { "Unverified discover vector command" }
                        check(node.params.isNotEmpty() && node.params.size % arity == 0)
                        node.params.toList().chunked(arity).forEachIndexed { index, values ->
                            val actual = if (index > 0 && node.type == 'M') "i"
                                else if (index > 0 && node.type == 'm') "j" else method
                            pathType.getDeclaredMethod(actual, *Array(arity) { float })
                                .invoke(path, *values.toTypedArray())
                        }
                    }
                }
            }
            add.invoke(null, builder, pathType.getDeclaredField("a").get(path),
                if (evenOddFill) 1 else 0, brush.newInstance(color.invoke(null, argb)), 1f, 1f, 0f, 0, 4f)
        }
        return requireNotNull(builderType.getDeclaredMethod("e").invoke(builder))
    }
}
