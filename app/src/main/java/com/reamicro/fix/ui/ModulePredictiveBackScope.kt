package com.reamicro.fix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventHistory
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Keep one dispatcher per window while changing the predictive gesture preference in place.
 *
 * This scope must run after the window rebinds its dispatcher and before its popup/layout handlers.
 * A standalone dispatcher is intentional: the parent constructor shares the processor and would
 * pass predictive progress directly to the content, bypassing the filter.
 */
@Composable
internal fun ModulePredictiveBackScope(animated: Boolean, content: @Composable () -> Unit) {
    val parent = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    if (parent == null) {
        content()
        return
    }
    val relay = remember(parent) { ModuleBackEventRelay(parent) }
    SideEffect { relay.animated = animated }
    DisposableEffect(relay) {
        relay.attach()
        onDispose { relay.dispose() }
    }
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides relay,
        content = content,
    )
}

/**
 * Relay completions and metadata, filtering only gesture progress.
 *
 * Completion without a started event uses NavigationEventInput's public completion path: handlers
 * receive the normal back/forward action without entering a predictive transition. The decision is
 * frozen at gesture start so a settings update cannot introduce half a gesture.
 */
internal class ModuleBackEventRelay(
    private val parent: NavigationEventDispatcher,
) : NavigationEventDispatcherOwner {
    override val navigationEventDispatcher = NavigationEventDispatcher()
    var animated = false

    private var attached = false
    private var disposed = false
    private var backStarted = false
    private var forwardStarted = false
    private var backAnimated = false
    private var forwardAnimated = false

    private val parentHandler: NavigationEventHandler<NavigationEventInfo> = object : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = NavigationEventInfo.None,
        isBackEnabled = false,
        isForwardEnabled = false,
    ) {
        override fun onBackStarted(event: NavigationEvent) {
            backStarted = true
            backAnimated = animated
            if (backAnimated) childInput.backStarted(event)
        }

        override fun onBackProgressed(event: NavigationEvent) {
            if (backStarted && backAnimated) childInput.backProgressed(event)
        }

        override fun onBackCompleted() {
            try {
                childInput.backCompleted()
            } finally {
                backStarted = false
                backAnimated = false
            }
        }

        override fun onBackCancelled() {
            try {
                if (backStarted && backAnimated) childInput.backCancelled()
            } finally {
                backStarted = false
                backAnimated = false
            }
        }

        override fun onForwardStarted(event: NavigationEvent) {
            forwardStarted = true
            forwardAnimated = animated
            if (forwardAnimated) childInput.forwardStarted(event)
        }

        override fun onForwardProgressed(event: NavigationEvent) {
            if (forwardStarted && forwardAnimated) childInput.forwardProgressed(event)
        }

        override fun onForwardCompleted() {
            try {
                childInput.forwardCompleted()
            } finally {
                forwardStarted = false
                forwardAnimated = false
            }
        }

        override fun onForwardCancelled() {
            try {
                if (forwardStarted && forwardAnimated) childInput.forwardCancelled()
            } finally {
                forwardStarted = false
                forwardAnimated = false
            }
        }
    }

    private val childInput = object : NavigationEventInput() {
        override fun onHasEnabledBackHandlersChanged(hasEnabledBackHandlers: Boolean) {
            parentHandler.isBackEnabled = hasEnabledBackHandlers
        }

        override fun onHasEnabledForwardHandlersChanged(hasEnabledForwardHandlers: Boolean) {
            parentHandler.isForwardEnabled = hasEnabledForwardHandlers
        }

        override fun onHistoryChanged(history: NavigationEventHistory) {
            val entries = history.mergedHistory
            val currentIndex = history.currentIndex
            if (currentIndex in entries.indices) {
                parentHandler.setInfo(
                    currentInfo = entries[currentIndex],
                    backInfo = entries.take(currentIndex),
                    forwardInfo = entries.drop(currentIndex + 1),
                )
            } else {
                parentHandler.setInfo(NavigationEventInfo.None, emptyList(), emptyList())
            }
        }

        fun backStarted(event: NavigationEvent) = dispatchOnBackStarted(event)
        fun backProgressed(event: NavigationEvent) = dispatchOnBackProgressed(event)
        fun backCompleted() = dispatchOnBackCompleted()
        fun backCancelled() = dispatchOnBackCancelled()
        fun forwardStarted(event: NavigationEvent) = dispatchOnForwardStarted(event)
        fun forwardProgressed(event: NavigationEvent) = dispatchOnForwardProgressed(event)
        fun forwardCompleted() = dispatchOnForwardCompleted()
        fun forwardCancelled() = dispatchOnForwardCancelled()
    }

    fun attach() {
        check(!disposed) { "A disposed back relay cannot be attached again" }
        if (attached) return
        parent.addHandler(parentHandler, NavigationEventDispatcher.PRIORITY_DEFAULT)
        navigationEventDispatcher.addInput(childInput)
        attached = true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (attached) {
            if (backStarted && backAnimated) {
                backStarted = false
                backAnimated = false
                childInput.backCancelled()
            }
            if (forwardStarted && forwardAnimated) {
                forwardStarted = false
                forwardAnimated = false
                childInput.forwardCancelled()
            }
            parentHandler.remove()
            navigationEventDispatcher.removeInput(childInput)
            attached = false
        }
        navigationEventDispatcher.dispose()
        backStarted = false
        forwardStarted = false
        backAnimated = false
        forwardAnimated = false
    }
}
