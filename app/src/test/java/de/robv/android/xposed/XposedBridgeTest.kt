package de.robv.android.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Executable

class XposedBridgeTest {
    @Test
    fun beforeHookCanModifyArguments() {
        val method = Target::class.java.getDeclaredMethod("join", String::class.java, String::class.java)
        val target = Target()
        val chain = fakeChain(method, target, arrayOf("a", "b")) { args ->
            target.join(args[0] as String, args[1] as String)
        }

        val result = XposedBridge.interceptForTest(
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[1] = "c"
                }
            },
            chain,
        )

        assertEquals("a-c", result)
    }

    @Test
    fun beforeHookCanReturnEarly() {
        val method = Target::class.java.getDeclaredMethod("join", String::class.java, String::class.java)
        val chain = fakeChain(method, Target(), arrayOf("a", "b")) {
            error("original should not run")
        }

        val result = XposedBridge.interceptForTest(
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "blocked"
                }
            },
            chain,
        )

        assertEquals("blocked", result)
    }

    @Test
    fun afterHookCanReplaceOriginalResult() {
        val method = Target::class.java.getDeclaredMethod("join", String::class.java, String::class.java)
        val target = Target()
        val chain = fakeChain(method, target, arrayOf("a", "b")) { args ->
            target.join(args[0] as String, args[1] as String)
        }

        val result = XposedBridge.interceptForTest(
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = "${param.result}!"
                }
            },
            chain,
        )

        assertEquals("a-b!", result)
    }

    @Test
    fun originalThrowableIsRethrownUnlessHookReplacesIt() {
        val method = Target::class.java.getDeclaredMethod("fail")
        val failure = IllegalStateException("boom")
        val chain = fakeChain(method, Target(), emptyArray()) {
            throw failure
        }

        val thrown = runCatching {
            XposedBridge.interceptForTest(XC_MethodHook(), chain)
        }.exceptionOrNull()

        assertSame(failure, thrown)
    }

    @Test
    fun beforeHookCanThrowEarly() {
        val method = Target::class.java.getDeclaredMethod("join", String::class.java, String::class.java)
        val failure = IllegalArgumentException("blocked")
        val chain = fakeChain(method, Target(), arrayOf("a", "b")) {
            error("original should not run")
        }

        val thrown = runCatching {
            XposedBridge.interceptForTest(
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.throwable = failure
                    }
                },
                chain,
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
    }

    @Test
    fun helperCanResolvePrimitiveBestMatch() {
        val target = Target()
        val result = XposedHelpers.callMethod(target, "add", 2, 3)

        assertEquals(5, result)
    }

    @Test
    fun helperCanAccessFields() {
        val target = Target()

        XposedHelpers.setIntField(target, "count", 7)
        XposedHelpers.setBooleanField(target, "enabled", true)

        assertEquals(7, XposedHelpers.getIntField(target, "count"))
        assertTrue(XposedHelpers.getBooleanField(target, "enabled"))
    }

    private class Target {
        private var count: Int = 0
        private var enabled: Boolean = false

        fun join(left: String, right: String): String = "$left-$right"
        fun add(left: Int, right: Int): Int = left + right
        fun fail(): String = error("boom")
    }

    private fun fakeChain(
        executable: Executable,
        thisObject: Any?,
        args: Array<Any?>,
        original: (Array<Any?>) -> Any?,
    ): XposedBridge.HookChain {
        return XposedBridge.HookChain(executable, thisObject, args, original)
    }
}
