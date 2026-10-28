package com.reamicro.fix.core

/** B4 由设置页真实调用链核实；未列出的门面不继承 B2 的短名。 */
object HostNativeUiAbi {
    fun supports(profileId: String): Boolean = profileId == "2.3.3-b2" || profileId in setOf("2.3.3-b4", "2.3.3-rc3")

    private val b4Methods = mapOf(
        "AboutEntry#a" to "a",
        "BackHandler#a" to "a",
        "Background#e" to "g",
        "Border#a" to "a",
        "Clickable#h" to "g",
        "Clickable#combined" to "h",
        "Clip#n" to "c",
        "ColumnPolicy#a" to "a",
        "Divider#c" to "c",
        "HomeSearchFooter#a" to "a",
        "LazyDsl#a" to "a",
        "LazyDsl#b" to "b",
        "ListItem#a" to "a",
        "ListItemColors#a" to "a",
        "Materialize#h" to "h",
        "Padding#j" to "j",
        "Padding#n" to "n",
        "Scaffold#J" to "a",
        "Size#c" to "c",
        "Size#d" to "d",
        "Size#f" to "f",
        "Size#n" to "n",
        "StateFactory#d" to "e",
        "Switch#a" to "a",
        "SwitchColors#a" to "b",
        "Text#b" to "b",
        "Unit#e" to "e",
        "Unit#g" to "g",
        "Updater#c" to "d",
        "Updater#d" to "e",
    )

    fun method(profileId: String, role: String, b2Name: String): String = when (profileId) {
        "2.3.3-b2" -> b2Name
        "2.3.3-b4", "2.3.3-rc3" -> b4Methods["$role#$b2Name"]
            ?: error("Unverified B4 UI member: $role#$b2Name")
        else -> error("Unverified native UI host: $profileId")
    }

    fun alignment(profileId: String, role: String): String = when (profileId) {
        "2.3.3-b2" -> when (role) {
            "start" -> "m"
            "center" -> "e"
            "centerVertically" -> "k"
            else -> error("Unknown alignment: $role")
        }
        "2.3.3-b4", "2.3.3-rc3" -> when (role) {
            "start" -> "n"
            "center" -> "f"
            "centerVertically" -> "l"
            else -> error("Unknown alignment: $role")
        }
        else -> error("Unverified native UI host: $profileId")
    }
}
