import com.reamicro.fix.online.download.onlineChapterUpdateMessage

fun checkOnlineChapterUpdateMessages() {
    for (styled in listOf(false, true)) {
        check(onlineChapterUpdateMessage(3, 0, 0, styled) == "已更新 3 章")
        check(onlineChapterUpdateMessage(3, 0, 2, styled) == "已重试 2 章，已更新 3 章")
        check(onlineChapterUpdateMessage(3, 1, 0, styled) == "已更新 3 章，1 章失败")
        check(onlineChapterUpdateMessage(3, 1, 2, styled) == "已重试 2 章，已更新 3 章，1 章失败")
        check(onlineChapterUpdateMessage(0, 1, 2, styled) == "已重试 2 章，1 章失败")
        check(onlineChapterUpdateMessage(0, 1, 0, styled) == "仍有 1 章失败")
        check(onlineChapterUpdateMessage(0, 0, 2, styled) == "已重试 2 章")
    }
    check(onlineChapterUpdateMessage(0, 0, 0, false) == "已是最新章节")
    check(onlineChapterUpdateMessage(0, 0, 0, true) == "章节已是最新，默认样式已同步")
    println("PASS: 16 update message cases; style synchronization never hides added chapter counts")
}
