package com.reamicro.fix.hook

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.hook.settings.*

internal fun ReaMicroSettingsHook.renderAbiText(vararg args: Any?) {
    if (com.reamicro.fix.core.HostNativeUiAbi.supports(HostSymbols.profileId)) b2Ui.text(args)
    else if (HostSymbols.abiProfileId == "2.3.3-b4") {
        require(args.size == 22)
        method(TEXT_KT_CLASS, TEXT_METHOD, 20).invoke(null, *args.filterIndexed { index, _ -> index != 3 && index != 5 }.toTypedArray())
    }
    else method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(null, *args)
}

internal fun ReaMicroSettingsHook.renderAbiListItem(vararg args: Any?) {
    if (com.reamicro.fix.core.HostNativeUiAbi.supports(HostSymbols.profileId)) b2Ui.listItem(args)
    else if (HostSymbols.abiProfileId == "2.3.3-b4") {
        require(args.size == 12)
        method(LIST_ITEM_KT_CLASS, LIST_ITEM_METHOD, 10).invoke(null, *args.filterIndexed { index, _ -> index != 2 && index != 8 }.toTypedArray())
    }
    else method(LIST_ITEM_KT_CLASS, LIST_ITEM_METHOD, 12).invoke(null, *args)
}

internal fun ReaMicroSettingsHook.renderAbiSwitch(checked: Boolean, onChanged: Any, composer: Any) {
    if (com.reamicro.fix.core.HostNativeUiAbi.supports(HostSymbols.profileId)) {
        b2Ui.settingsSwitch(checked, onChanged, composer)
    } else if (HostSymbols.abiProfileId == "2.3.3-b4") {
        method(SWITCH_KT_CLASS, SWITCH_METHOD, 8).invoke(
            null, checked, onChanged, switchModifier(), false, switchColors(composer), composer, 0, 88,
        )
    } else {
        method(SWITCH_KT_CLASS, SWITCH_METHOD, 10).invoke(
            null, checked, onChanged, switchModifier(), null, false,
            switchColors(composer), null, composer, 0, 88,
        )
    }
}
