package com.reamicro.fix.core

internal object HomeDefaultPagePolicy {
    fun initialPreference(initialized: Boolean, groups: List<*>, stored: Boolean?): Boolean? =
        stored.takeIf { !initialized && groups.isNotEmpty() }
}
