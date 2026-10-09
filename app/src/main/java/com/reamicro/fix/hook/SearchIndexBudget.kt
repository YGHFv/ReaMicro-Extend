package com.reamicro.fix.hook

internal class SearchIndexBudget(private val limit: Long = 16L * 1024 * 1024) {
    private var used = 0L
    var cacheable = true
        private set

    fun retain(weight: Long): Boolean {
        if (!cacheable) return false
        if (weight < 0 || weight > limit - used) {
            cacheable = false
            return false
        }
        used += weight
        return true
    }
}
