// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.nav.core

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * The navigation back stack: a [SnapshotStateList] of [NavKey].
 *
 * This is the public type name; callers may operate the list directly (add/removeAt/...) or via a
 * [NavController]. Persistence across configuration changes and process death is provided by
 * [rememberNavBackStack].
 */
public typealias NavBackStack = SnapshotStateList<NavKey>

/**
 * Non-composable constructor for a [NavBackStack], primarily for tests and off-composition setup.
 */
public fun navBackStackOf(vararg elements: NavKey): NavBackStack = elements.toList().toMutableStateList()
