import com.reamicro.fix.hook.ReaderMarginsSnapshot
import com.reamicro.fix.hook.epubContainerMarksIndex
import org.json.JSONObject

class HostMargins(
    val top: Int, val bottom: Int, val left: Int, val right: Int,
    val topOuter: Int, val bottomOuter: Int,
)

fun main() {
    checkOnlineChapterUpdateMessages()
    checkReaderBottomBarComposeScope()
    val prefix = arrayOf(Any::class.java, Any::class.java, Any::class.java, Boolean::class.javaPrimitiveType!!)
    val legacy = prefix + arrayOf(List::class.java, List::class.java, String::class.java)
    val current = prefix + arrayOf(HostMargins::class.java, List::class.java, List::class.java, String::class.java)
    check(epubContainerMarksIndex(legacy) == 4)
    check(epubContainerMarksIndex(current) == 5)
    check(epubContainerMarksIndex(prefix) == -1)
    check(epubContainerMarksIndex(arrayOf(List::class.java)) == -1)

    // 用不对称数值检测参数顺序，包含零值和外边距，不能退化成旧版统一 padding。
    val expected = ReaderMarginsSnapshot(11, 22, 33, 44, 0, 66)
    val json = JSONObject(expected.toJson().toString())
    check(ReaderMarginsSnapshot.fromJson(json, 99) == expected)
    check(ReaderMarginsSnapshot.fromHost(HostMargins(11, 22, 33, 44, 0, 66)) == expected)
    val host = HostMargins::class.java.getConstructor(*Array(6) { Integer.TYPE })
        .newInstance(*expected.arguments())
    check(ReaderMarginsSnapshot.fromHost(host) == expected)

    // 旧账号快照必须覆盖六个方向，不能遗留上一账号的单独边距。
    val migrated = ReaderMarginsSnapshot.fromJson(null, 17)
    check(migrated == ReaderMarginsSnapshot(17, 17, 17, 17, 0, 0))
    check(ReaderMarginsSnapshot.fromJson(JSONObject(), 17) == migrated)
    val partial = ReaderMarginsSnapshot.fromJson(JSONObject().put("left", 0).put("bottomOuter", 9), 17)
    check(partial == ReaderMarginsSnapshot(17, 17, 0, 17, 0, 9))
    println("PASS: reader host signatures, six-direction margins, JSON round trip and legacy migration")
}
