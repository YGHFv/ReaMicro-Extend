package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.typeMatches
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostClassOrNull

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.hook.settings.CLICKABLE_KT_CLASS
import com.reamicro.fix.hook.settings.COMPOSER_CLASS
import com.reamicro.fix.hook.settings.LOG_PREFIX
import com.reamicro.fix.hook.settings.PADDING_KT_CLASS
import com.reamicro.fix.hook.settings.SPACER_KT_CLASS
import com.reamicro.fix.hook.settings.SPACER_METHOD
import com.reamicro.fix.hook.settings.TEXT_KT_CLASS
import com.reamicro.fix.hook.settings.TEXT_METHOD
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Method

internal class DiscoverCardHook(private val classLoader: ClassLoader) {

    private val communityDepth = ThreadLocal.withInitial { 0 }

    private val communityTextSeen = ThreadLocal.withInitial { false }

    private val communityRowOpened = ThreadLocal.withInitial { false }

    private val communityRowScope = ThreadLocal<Any?>()

    private val communityInjected = ThreadLocal.withInitial { false }

    private val communityRowBase = ThreadLocal<Any?>()

    private val communityRowClick = ThreadLocal<Any?>()

    private val communityRowStripped = ThreadLocal.withInitial { false }

    private val communityBookSeen = ThreadLocal.withInitial { false }

    private val communityLabelDecorated = ThreadLocal.withInitial { false }

    private val communityLabelPending = ThreadLocal.withInitial { false }

    private val communitySpacerWeight = ThreadLocal<Any?>()

    fun install() {
        HookInstallReport.installAll(
            FEATURE_ID,
            listOf(
                "communityLifecycle" to ::hookCommunityLifecycle,
                "communityText" to ::hookCommunityText,
                "rowScopeWeight" to ::hookRowScopeWeight,
                "rowTrailingIcon" to ::hookRowTrailingIcon,
                "rowClickable" to ::hookRowClickable,
                "rowPadding" to ::hookRowPadding,
                "rowBookIcon" to ::hookRowBookIcon,
                "rowIconImage" to ::hookRowIconImage,
                "rowLabelText" to ::hookRowLabelText,
                "rowSpacer" to ::hookRowSpacer,
            ),
        )
    }

    private fun hookCommunityLifecycle() {
        val target = findCommunityMethod()
            ?: error("$HOST_COMMUNITY.$COMMUNITY_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val depth = (communityDepth.get() ?: 0) + 1
                    communityDepth.set(depth)
                    if (depth == 1) {

                        communityTextSeen.set(false)
                        communityRowOpened.set(false)
                        communityInjected.set(false)
                        communityRowScope.remove()
                        communityRowBase.remove()
                        communityRowClick.remove()
                        communityRowStripped.set(false)
                        communityBookSeen.set(false)
                        communityLabelDecorated.set(false)
                        communityLabelPending.set(false)
                        communitySpacerWeight.remove()
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val depth = (communityDepth.get() ?: 1) - 1
                    if (depth <= 0) {
                        communityDepth.remove()
                        communityTextSeen.remove()
                        communityRowOpened.remove()
                        communityInjected.remove()
                        communityRowScope.remove()
                        communityRowBase.remove()
                        communityRowClick.remove()
                        communityRowStripped.remove()
                        communityBookSeen.remove()
                        communityLabelDecorated.remove()
                        communityLabelPending.remove()
                        communitySpacerWeight.remove()
                    } else {
                        communityDepth.set(depth)
                    }
                }
            },
        )
        if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
            XposedBridge.deoptimize(target)
        }
    }

    private fun findCommunityMethod(): Method? {
        val clazz = classLoader.hostClass(HOST_COMMUNITY)

        return clazz.declaredMethods
            .filter { it.hostNameMatches(COMMUNITY_METHOD) }
            .maxByOrNull { it.parameterTypes.size }
    }

    private fun hookCommunityText() {
        val clazz = classLoader.hostClassOrNull(STRING0_CLASS)
        if (clazz == null) {
            // B4 已把社区标题内联为常量，不再存在资源门面；社区生命周期仍可独立工作。
            XposedBridge.log("$LOG_PREFIX B4 community text resource hook skipped: class absent")
            return
        }
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(GET_COMMUNITY_METHOD) && it.parameterTypes.size == 1
        } ?: run {
            XposedBridge.log("$LOG_PREFIX community text resource hook skipped: method absent")
            return
        }
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inCommunity()) {
                        communityTextSeen.set(true)
                    }
                }
            },
        )
    }

    private fun hookRowScopeWeight() {
        // B4 RowScope 只剩抽象声明，实际调用位于已核实的 RowScopeInstance。
        val clazz = classLoader.hostClass(
            if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") "ReaMicroHost.RowScopeInstance"
            else ROW_SCOPE_CLASS,
        )
        val target = clazz.declaredMethods.firstOrNull {
            !java.lang.reflect.Modifier.isAbstract(it.modifiers) &&
                (it.hostNameMatches(WEIGHT_DEFAULT_METHOD) || it.hostNameMatches(WEIGHT_METHOD) ||
                    (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4" && it.name == "b")) &&
                (it.parameterTypes.size == WEIGHT_PARAMETER_COUNT ||
                    it.parameterTypes.size == B4_WEIGHT_PARAMETER_COUNT)
        } ?: error("$ROW_SCOPE_CLASS weight method not found")
        val b4InstanceMethod = !java.lang.reflect.Modifier.isStatic(target.modifiers)
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityTextSeen.get() != true) return
                    if (communityInjected.get() == true) return
                    val scope = if (b4InstanceMethod) param.thisObject else param.args?.getOrNull(0)
                    if (scope == null) return
                    if (communityRowScope.get() == null) {
                        communityRowScope.set(scope)
                    }
                    if (communityRowOpened.get() != true) {
                        communityRowOpened.set(true)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityTextSeen.get() != true) return
                    if (communityInjected.get() == true) return
                    if (communitySpacerWeight.get() != null) return
                    param.result?.let { communitySpacerWeight.set(it) }
                }
            },
        )
    }

    private fun hookRowTrailingIcon() {
        val clazz = XposedHelpers.findClass(ICON_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(ICON_METHOD) &&
                it.parameterTypes.size == ICON_PARAMETER_COUNT &&
                typeMatches(it.parameterTypes.firstOrNull(), IMAGE_VECTOR_CLASS)
        } ?: error("$ICON_KT_CLASS.$ICON_METHOD ImageVector overload not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityInjected.get() == true) return
                    if (communityRowOpened.get() != true) return
                    val rowScope = communityRowScope.get() ?: return
                    val composer = param.args?.getOrNull(ICON_ARG_COMPOSER) ?: return
                    communityInjected.set(true)

                    renderDiscoverButton(
                        classLoader = classLoader,
                        rowScope = rowScope,
                        composer = composer,
                        onHostRowClick = hostRowClickInvoker(),
                        onDiscoverClick = ::openDiscoverPage,
                    )

                    param.result = null
                }
            },
        )
    }

    private fun hookRowClickable() {
        val clazz = classLoader.hostClass(CLICKABLE_KT_CLASS)
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(HOST_CLICKABLE_DEFAULT_METHOD) &&
                (it.parameterTypes.size == HOST_CLICKABLE_PARAMETER_COUNT ||
                    it.parameterTypes.size == B4_CLICKABLE_PARAMETER_COUNT)
        } ?: error("$CLICKABLE_KT_CLASS clickable method not found")
        val b4Clickable = target.parameterTypes.size == B4_CLICKABLE_PARAMETER_COUNT
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityRowStripped.get() == true) return
                    runCatching {
                        val args = param.args ?: return@runCatching
                        val onClickIndex = if (b4Clickable) B4_CLICKABLE_ARG_ON_CLICK else HOST_CLICKABLE_ARG_ON_CLICK
                        val onClick = args.getOrNull(onClickIndex) ?: return@runCatching
                        if (b4Clickable) {
                            if (!typeMatches(onClick.javaClass, FUNCTION0_CLASS)) return@runCatching
                            // 社区有多个同形回调，只拆分真实书院行的 byte=12 分支。
                            if (!typeMatches(onClick.javaClass, "ReaMicroHost.CommunityRowClick") ||
                                onClick.javaClass.hostAbiField("a").getByte(onClick) != 12.toByte()) return@runCatching
                        } else if (onClick.javaClass.name != HOST_COMMUNITY_ROW_CLICK) {
                            return@runCatching
                        }
                        communityRowClick.set(onClick)
                        communityRowBase.set(args.getOrNull(0))
                        communityRowStripped.set(true)

                        param.result = args[0]
                    }.onFailure { probeFailed("rowClickable", it) }
                }
            },
        )
    }

    private fun hookRowPadding() {
        val clazz = XposedHelpers.findClass(PADDING_KT_CLASS, classLoader)
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(PADDING_ALL_METHOD) && it.parameterTypes.size == PADDING_ALL_PARAMETER_COUNT
        } ?: error("$PADDING_KT_CLASS.$PADDING_ALL_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityRowStripped.get() != true) return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    runCatching {
                        val args = param.args ?: return@runCatching
                        if (args.getOrNull(0) !== communityRowBase.get()) return@runCatching
                        val value = args.getOrNull(1) as? Float ?: return@runCatching
                        if (value != hook.udp(ROW_PADDING_DP)) return@runCatching
                        communityRowStripped.set(false)

                        communityLabelPending.set(true)
                        param.result = hook.fillMaxWidthModifier(args[0])
                    }.onFailure { probeFailed("rowPadding", it) }
                }
            },
        )
    }

    private fun hookRowBookIcon() {
        val clazz = classLoader.hostClassOrNull(BOOK_KT_CLASS) ?: run {
            // B4 内联了 Colored.Book 图标，旧 BookKt 门面不再可 hook。
            XposedBridge.log("$LOG_PREFIX B4 discover book icon hook skipped: class absent")
            return
        }
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(BOOK_METHOD) && it.parameterTypes.size == 1
        } ?: error("$BOOK_KT_CLASS.$BOOK_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    communityBookSeen.set(true)
                }
            },
        )
    }

    private fun hookRowIconImage() {
        val clazz = XposedHelpers.findClass(IMAGE_KT_CLASS, classLoader)
        // Image 的 ImageVector 重载参数个数随宿主 Compose 版本变化（b1 为 10，b2/b3 收窄到 6），
        // 必须按「首参 ImageVector + 带 Composer」定位，默认掩码下标按实际参数个数推导。
        val target = clazz.declaredMethods.firstOrNull { method ->
            method.hostNameMatches(IMAGE_METHOD) &&
                typeMatches(method.parameterTypes.firstOrNull(), IMAGE_VECTOR_CLASS) &&
                method.parameterTypes.any { parameter -> parameter == composerClass }
        } ?: error("$IMAGE_KT_CLASS.$IMAGE_METHOD ImageVector overload not found")
        val defaultMaskIndex = target.parameterTypes.size - 1
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityBookSeen.get() != true &&
                        !(com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4" &&
                            communityLabelPending.get() == true)) return
                    if (communityInjected.get() == true) return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    val args = param.args ?: return
                    val composer = composerOf(args) ?: return
                    communityBookSeen.set(false)
                    runCatching {
                        args[IMAGE_ARG_MODIFIER] = hook.leftCellIconModifier(composer, hostRowClickInvoker())

                        clearDefaultBit(args, defaultMaskIndex, IMAGE_MODIFIER_DEFAULT_BIT)
                    }.onFailure { probeFailed("rowIconImage", it) }
                }
            },
        )
    }

    private fun hookRowLabelText() {
        val clazz = XposedHelpers.findClass(TEXT_KT_CLASS, classLoader)
        // Text(String, ...) 重载同样随 Compose 版本变参数（b1 22 / b2 21 / b3 20），按首参 String 定位。
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(TEXT_METHOD) &&
                it.parameterTypes.firstOrNull() == String::class.java &&
                it.parameterTypes.any { parameter -> parameter == composerClass }
        } ?: error("$TEXT_KT_CLASS.$TEXT_METHOD not found")
        val defaultMaskIndex = target.parameterTypes.size - 1
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityLabelDecorated.get() == true) return
                    if (communityLabelPending.get() != true) return
                    val args = param.args ?: return
                    if (args.getOrNull(TEXT_ARG_MODIFIER) != null) return
                    val composer = composerOf(args) ?: return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    communityLabelDecorated.set(true)
                    communityLabelPending.set(false)
                    runCatching {
                        args[TEXT_ARG_MODIFIER] =
                            hook.leftCellLabelModifier(composer, hostRowClickInvoker())

                        clearDefaultBit(args, defaultMaskIndex, TEXT_MODIFIER_DEFAULT_BIT)
                        if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
                            communityTextSeen.set(true)
                        }
                    }.onFailure { probeFailed("rowLabelText", it) }
                }
            },
        )
    }

    private fun hookRowSpacer() {
        if (com.reamicro.fix.core.HostSymbols.abiProfileId == "2.3.3-b4") {
            val target = classLoader.hostClass("ReaMicroHost.Spacer").hostAbiMethod(
                "b", classLoader.hostClass(COMPOSER_CLASS),
                classLoader.hostClass("androidx.compose.ui.Modifier"),
            )
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity() || communityInjected.get() == true) return
                    val weighted = communitySpacerWeight.get() ?: return
                    if (param.args[1] !== weighted) return
                    val scope = communityRowScope.get() ?: return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    communitySpacerWeight.set(null)
                    param.args[1] = hook.leftCellSpacerModifier(scope, param.args[0], hostRowClickInvoker())
                }
            })
            return
        }
        val clazz = classLoader.hostClassOrNull(SPACER_KT_CLASS) ?: run {
            // B4 直接用 RowScope 权重布局，不再调用 SpacerKt。
            XposedBridge.log("$LOG_PREFIX B4 discover spacer hook skipped: class absent")
            return
        }
        val target = clazz.declaredMethods.firstOrNull {
            it.hostNameMatches(SPACER_METHOD) && it.parameterTypes.size == SPACER_PARAMETER_COUNT
        } ?: error("$SPACER_KT_CLASS.$SPACER_METHOD not found")
        XposedBridge.hookMethod(
            target,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!inCommunity()) return
                    if (communityInjected.get() == true) return
                    val weighted = communitySpacerWeight.get() ?: return
                    val args = param.args ?: return
                    if (args.getOrNull(SPACER_ARG_MODIFIER) !== weighted) return
                    val rowScope = communityRowScope.get() ?: return
                    val composer = composerOf(args) ?: return
                    val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return
                    communitySpacerWeight.set(null)
                    runCatching {
                        args[SPACER_ARG_MODIFIER] =
                            hook.leftCellSpacerModifier(rowScope, composer, hostRowClickInvoker())
                    }.onFailure { probeFailed("rowSpacer", it) }
                }
            },
        )
    }

    private fun hostRowClickInvoker(): () -> Unit {
        val click: Any? = communityRowClick.get()
        return {
            if (click != null) {
                runCatching { click.javaClass.hostMethod(INVOKE_METHOD).invoke(click) }
                    .onFailure { probeFailed("hostRowClick", it) }
            } else {
                probeFailed("hostRowClick", IllegalStateException("host row onClick lambda not captured"))
            }
        }
    }

    private fun composerOf(args: Array<Any?>): Any? {
        val cls = composerClass ?: return null
        return args.firstOrNull { it != null && cls.isInstance(it) }
    }

    private val composerClass: Class<*>? by lazy {
        classLoader.hostClassOrNull(COMPOSER_CLASS)
    }

    private fun probeFailed(where: String, error: Throwable) {
        XposedBridge.logAlways("$LOG_PREFIX discover $where failed: ${error.message}")
    }

    private fun clearDefaultBit(args: Array<Any?>?, maskIndex: Int, bit: Int) {
        val mask = args?.getOrNull(maskIndex) as? Int ?: return
        args[maskIndex] = mask and bit.inv()
    }

    private fun inCommunity(): Boolean = (communityDepth.get() ?: 0) > 0

    private fun openDiscoverPage() {
        if (!ReaMicroSettingsHook.openDiscoverPage()) {
            XposedBridge.log("$LOG_PREFIX discover page unavailable (host navigation not captured yet)")
        }
    }

    private companion object {
        const val FEATURE_ID = "DiscoverCardHook"

        const val HOST_COMMUNITY = "app.zhendong.reamicro.ui.profile.components.CommunityKt"
        const val COMMUNITY_METHOD = "Community"
        const val STRING0_CLASS = "reamicro.composeapp.generated.resources.String0_commonMainKt"
        const val GET_COMMUNITY_METHOD = "getCommunity"
        const val ROW_SCOPE_CLASS = "androidx.compose.foundation.layout.RowScope"
        const val WEIGHT_DEFAULT_METHOD = "weight\$default"
        const val WEIGHT_METHOD = "weight"
        const val WEIGHT_PARAMETER_COUNT = 6
        const val B4_WEIGHT_PARAMETER_COUNT = 2
        const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
        const val ICON_METHOD = "Icon-ww6aTOc"
        const val ICON_PARAMETER_COUNT = 7

        const val IMAGE_VECTOR_CLASS = "androidx.compose.ui.graphics.vector.ImageVector"

        const val IMAGE_KT_CLASS = "androidx.compose.foundation.ImageKt"
        const val IMAGE_METHOD = "Image"

        const val IMAGE_PARAMETER_COUNT = 10
        const val TEXT_PARAMETER_COUNT = 22

        const val SPACER_PARAMETER_COUNT = 3

        const val ICON_ARG_COMPOSER = 4

        const val ICON_ARG_MODIFIER = 2

        const val HOST_CLICKABLE_DEFAULT_METHOD = "clickable-oSLSa3U\$default"
        const val HOST_CLICKABLE_PARAMETER_COUNT = 8
        const val B4_CLICKABLE_PARAMETER_COUNT = 6

        const val HOST_CLICKABLE_ARG_ON_CLICK = 5
        const val B4_CLICKABLE_ARG_ON_CLICK = 4
        const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"

        const val HOST_COMMUNITY_ROW_CLICK =
            "app.zhendong.reamicro.ui.profile.components.CommunityKt\$\$ExternalSyntheticLambda3"

        const val PADDING_ALL_METHOD = "padding-3ABfNKs"
        const val PADDING_ALL_PARAMETER_COUNT = 2

        const val ROW_PADDING_DP = 16

        const val BOOK_KT_CLASS = "app.zhendong.reamicro.arch.icons.colored.BookKt"
        const val BOOK_METHOD = "getBook"

        const val IMAGE_ARG_MODIFIER = 2

        const val IMAGE_ARG_DEFAULT_MASK = 9

        const val IMAGE_MODIFIER_DEFAULT_BIT = 0x4

        const val TEXT_ARG_MODIFIER = 1

        const val TEXT_ARG_DEFAULT_MASK = 21

        const val TEXT_MODIFIER_DEFAULT_BIT = 0x2

        const val SPACER_ARG_MODIFIER = 0

        const val INVOKE_METHOD = "invoke"
    }
}
