package com.reamicro.fix.reload

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.reamicro.fix.core.HostCompositionBindings
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 保留 Activity 和宿主原始回调，重新创建根 Composition。 */
internal object HostCompositionReload {
    private val host get() = requireNotNull(HostCompositionBindings.current) { "宿主原地热重载绑定尚未就绪" }
    private fun root(activity: Activity): View {
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        return (0 until container.childCount).map(container::getChildAt).single { it.javaClass == host.type("root") }
    }
    fun capture(activity: Activity): Array<Any> {
        val api = host
        check(api.type("activity").isInstance(activity))
        val state = api.field("state").get(root(activity))
        val content = api.method("stateGet").invoke(state)
        check(content?.javaClass == api.type("wrapper")) { "宿主根内容已变化" }
        val callback = requireNotNull(api.field("callback").get(content))
        check(callback.javaClass == api.type("callback") &&
            api.field("callbackActivity").get(callback) === activity) {
            "宿主根回调不是已验证的入口"
        }
        val key = api.field("key").getInt(content)
        val tracked = api.field("tracked").getBoolean(content)
        // Method/Field/Constructor are bootstrap objects referring only to host classes. Keep
        // the exact validated member, including its return type when a bridge overload exists.
        // The new generation can then restore the UI even when its DexKit initialization fails.
        val rootConstructor = api.constructor("root")
        val wrapperConstructor = api.constructor("wrapper")
        val setContent = api.method("setContent")
        val navigation = api.field("navCache")
        val callbackActivity = api.field("callbackActivity")
        return arrayOf(activity, callback, key, tracked, rootConstructor, wrapperConstructor, setContent,
            navigation, callbackActivity)
    }

    /** 后台安装完成后释放旧页面的跳过状态，按已保存导航重新进入全部可见内容。 */
    fun refresh(activity: Activity) {
        val state = capture(activity)
        var detached = false
        try {
            detach(state) { detached = true }
            restore(state)
        } catch (error: Throwable) {
            if (detached) try {
                // dispose 后移除 View 也可能失败，仍附着的旧根页面同样需要替换。
                restore(state)
            } catch (restoreError: Throwable) {
                if (restoreError !== error) error.addSuppressed(restoreError)
            }
            throw error
        }
    }

    /** 已运行过的宿主根回调可能内联导航调用，解除内联后再创建新页面。 */
    fun prepareCallbacks() {
        val api = host
        val composer = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).composerClass
        val methods = listOf(api.type("callback"), api.type("wrapper")).flatMap { type ->
            type.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) && method.parameterTypes.let { parameters ->
                    parameters.size == 2 &&
                        parameters[0] in listOf(Any::class.java, composer) &&
                        parameters[1] in listOf(Any::class.java, Integer.TYPE)
                }
            }
        }
        check(methods.isNotEmpty()) { "宿主根回调入口缺失" }
        methods.forEach(com.reamicro.fix.xposed.HookRuntime::deoptimize)
    }

    fun detach(state: Array<Any>, beforeMutation: () -> Unit) {
        val activity = state[0] as Activity
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = root(activity)
        // 先释放 remember/effect/导航闭包，再移除旧 View，整个任务及 Activity 留在前台。
        com.reamicro.fix.hook.ReaMicroSettingsHook.prepareNavigationReload(activity)
        beforeMutation()
        host.method("dispose").invoke(root)
        host.field("navCache").set(null, null)
        container.removeView(root)
    }

    fun restore(state: Array<*>) {
        val activity = state[0] as Activity
        check(!activity.isDestroyed && !activity.isFinishing) { "宿主页面已退出" }
        val members = restoreMembers(state)
        val loader = activity.javaClass.classLoader
        check(listOf<Member>(members.root, members.wrapper, members.content, members.navigation, members.callbackActivity).all {
            it.declaringClass.classLoader === loader
        } && state[1]?.javaClass?.classLoader === loader) { "热重载恢复状态不属于宿主" }
        check(members.callbackActivity.get(state[1]) === activity) { "宿主根回调不是已验证的入口" }
        check(Modifier.isStatic(members.navigation.modifiers)) { "宿主导航状态不是静态字段" }
        val root = members.root.newInstance(activity) as View
        // 新 wrapper 不携带旧 Composition 的 RecomposeScope，原始回调完全属于宿主。
        val content = members.wrapper.newInstance(state[2], state[3], state[1])
        members.content.invoke(root, content)
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        // A failed detach may leave the disposed root attached. Replace it as part of rollback.
        val previous = (0 until container.childCount).map(container::getChildAt)
            .filter { it.javaClass == members.root.declaringClass }
        check(previous.size <= 1) { "宿主存在多个根页面" }
        members.navigation.set(null, null)
        previous.forEach(container::removeView)
        container.addView(root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun restoreIfMissing(state: Array<*>) {
        val activity = state[0] as Activity
        val type = restoreMembers(state).root.declaringClass
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        if ((0 until container.childCount).none { container.getChildAt(it).javaClass == type }) restore(state)
    }

    private class RestoreMembers(
        val root: Constructor<*>,
        val wrapper: Constructor<*>,
        val content: Method,
        val navigation: Field,
        val callbackActivity: Field,
    )

    private fun restoreMembers(state: Array<*>): RestoreMembers {
        require(state.size >= 9) { "Incomplete neutral composition state" }
        return RestoreMembers(
            state[4] as Constructor<*>, state[5] as Constructor<*>,
            state[6] as Method, state[7] as Field, state[8] as Field,
        )
    }
}
