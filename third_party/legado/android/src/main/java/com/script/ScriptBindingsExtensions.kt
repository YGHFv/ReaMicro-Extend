package com.script

import org.mozilla.javascript.Context

inline fun buildScriptBindings(block: (bindings: ScriptBindings) -> Unit): ScriptBindings {
    val bindings = ScriptBindings()
    com.script.rhino.enterRhinoContext()
    try {
        block(bindings)
    } finally {
        Context.exit()
    }
    return bindings
}
