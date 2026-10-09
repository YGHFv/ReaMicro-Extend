import com.reamicro.fix.hook.ReaderBottomBarComposeScope
import com.reamicro.fix.hook.ReaderBottomBarContentBinding
import com.reamicro.fix.hook.readerBottomBarContentBinding

fun checkReaderBottomBarComposeScope() {
    val book = "app.zhendong.reamicro.data.db.entity.Book"
    val receiver = "app.zhendong.reamicro.arch.IntentReceiver"
    val status = "app.zhendong.reamicro.ui.reader.UiStatus"
    val composer = "androidx.compose.runtime.Composer"
    val function = "kotlin.jvm.functions.Function0"
    val flow = "kotlinx.coroutines.flow.Flow"
    val current = listOf(book, "int", function, function, function, flow, receiver, status,
        "androidx.compose.animation.AnimatedContentScope", status, composer, "int")
    check(readerBottomBarContentBinding("ReaderBottomBar\$lambda\$4\$0\$2\$1", current) == ReaderBottomBarContentBinding(6, 0))
    check(readerBottomBarContentBinding("ReaderBottomBar\$lambda\$99", current) == ReaderBottomBarContentBinding(6, 0))
    check(readerBottomBarContentBinding("ReaderBottomBar\$lambda\$4\$0",
        listOf(status, book, function, function, function, flow, receiver, composer, "int")) == ReaderBottomBarContentBinding(6, 1))
    check(readerBottomBarContentBinding("ReaderBottomBar", current) == null)
    check(readerBottomBarContentBinding("OtherMenu\$lambda\$1", current) == null)
    check(readerBottomBarContentBinding("ReaderBottomBar\$lambda\$1", listOf(book, receiver)) == null)
    check(readerBottomBarContentBinding("ReaderBottomBar\$lambda\$1", listOf(book, composer)) == null)
    check(readerBottomBarContentBinding("ReaderBottomBar\$lambda\$1", listOf(receiver, composer)) == null)

    val scope = ReaderBottomBarComposeScope()
    check(!scope.active)
    scope.enter()
    scope.exit()
    check(!scope.active)
    // 模拟首次打开：父菜单已经返回，动画回调之后才绘制图标并绑定点击。
    scope.enter()
    check(scope.active)
    scope.enter()
    scope.exit()
    check(scope.active)
    val worker = Thread { check(!scope.active); scope.enter(); check(scope.active); scope.exit() }
    var workerError: Throwable? = null
    worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> workerError = error }
    worker.start()
    worker.join()
    check(workerError == null) { workerError.toString() }
    check(scope.active)
    scope.exit()
    check(!scope.active)
    try {
        scope.enter()
        error("render failure")
    } catch (_: IllegalStateException) {
        // Hook 的 after 回调在原方法抛异常时也必须清理范围。
    } finally {
        scope.exit()
    }
    check(!scope.active)
    scope.exit()
    check(!scope.active)
    println("PASS: deferred menu composition, nested scopes, cleanup, thread isolation and callback signatures")
}
