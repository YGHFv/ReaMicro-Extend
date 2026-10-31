package com.reamicro.fix.reload

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.reamicro.fix.core.HostSymbols

/** 只支持已核对的正式版宿主：替换根 Composition，不销毁 Activity 或重放 onCreate。 */
internal object HostCompositionReload {
    fun capture(activity: Activity): Array<Any> {
        check(HostSymbols.profileId == "2.3.3") { "当前宿主尚未验证原地热重载" }
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = (0 until container.childCount).map(container::getChildAt)
            .single { it.javaClass.name == "hq1" }
        val loader = activity.classLoader
        val state = root.javaClass.getDeclaredField("j").get(root)
        val content = state.javaClass.getMethod("getValue").invoke(state)
        check(content?.javaClass?.name == "ro1") { "宿主根内容已变化" }
        val callback = content.javaClass.getDeclaredField("c").get(content)
        check(callback.javaClass.name == "ch" &&
            callback.javaClass.getDeclaredField("c").get(callback) === activity) {
            "宿主根回调不是已验证的入口"
        }
        check(callback.javaClass.classLoader === loader)
        loader.loadClass("v0").getDeclaredMethod("e")
        loader.loadClass("hq1").getConstructor(activity.javaClass)
        loader.loadClass("hq1").getDeclaredMethod("setContent", loader.loadClass("sq3"))
        val key = content.javaClass.getDeclaredField("a").getInt(content)
        val tracked = content.javaClass.getDeclaredField("b").getBoolean(content)
        return arrayOf(activity, callback, key, tracked)
    }

    fun detach(state: Array<Any>) {
        val activity = state[0] as Activity
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = (0 until container.childCount).map(container::getChildAt)
            .single { it.javaClass.name == "hq1" }
        // 先释放 remember/effect/导航闭包，再移除旧 View，整个任务及 Activity 留在前台。
        activity.classLoader.loadClass("v0").getDeclaredMethod("e").invoke(root)
        container.removeView(root)
        activity.classLoader.loadClass("qkd").getDeclaredField("c").set(null, null)
    }

    fun restore(state: Array<*>) {
        val activity = state[0] as Activity
        check(!activity.isDestroyed && !activity.isFinishing) { "宿主页面已退出" }
        val loader = activity.classLoader
        val root = loader.loadClass("hq1").getConstructor(activity.javaClass).newInstance(activity) as View
        // 新 wrapper 不携带旧 Composition 的 RecomposeScope，原始回调完全属于宿主。
        val content = loader.loadClass("ro1").getConstructor(
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Any::class.java,
        ).newInstance(state[2], state[3], state[1])
        root.javaClass.getDeclaredMethod("setContent", loader.loadClass("sq3")).invoke(root, content)
        activity.findViewById<ViewGroup>(android.R.id.content).addView(root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
