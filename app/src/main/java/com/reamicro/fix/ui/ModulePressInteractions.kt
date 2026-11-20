package com.reamicro.fix.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.node.DelegatableNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Miuix's immediate press observer and the Card's click owner can overlap on one physical press.
 * Keep feedback active until both have ended, so the observer cannot release a held long click.
 * State belongs to each collector and is discarded when its indication node is detached.
 */
internal class ModuleJoinedPressInteractionSource(
    source: InteractionSource,
) : InteractionSource {
    override val interactions: Flow<Interaction> = flow {
        // Most collectors never receive a press. Allocate only on first touch; two slots cover
        // the immediate observer and click owner without hash buckets or per-event map entries.
        var active: ArrayList<PressInteraction.Press>? = null
        var visiblePress: PressInteraction.Press? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    val presses = active ?: ArrayList<PressInteraction.Press>(2).also { active = it }
                    if (presses.none { it === interaction }) {
                        presses.add(interaction)
                        if (presses.size == 1) {
                            visiblePress = interaction
                            emit(interaction)
                        }
                    }
                }
                is PressInteraction.Release -> {
                    val presses = active
                    val index = presses?.indexOfFirst { it === interaction.press } ?: -1
                    if (index >= 0) {
                        presses!!.removeAt(index)
                        if (presses.isEmpty()) {
                            visiblePress?.let { emit(PressInteraction.Release(it)) }
                            visiblePress = null
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    val presses = active
                    val index = presses?.indexOfFirst { it === interaction.press } ?: -1
                    if (index >= 0) {
                        presses!!.removeAt(index)
                        if (presses.isEmpty()) {
                            visiblePress?.let { emit(PressInteraction.Cancel(it)) }
                            visiblePress = null
                        }
                    }
                }
                else -> emit(interaction)
            }
        }
    }
}

/** Reuses the public Miuix animation/overlay with coherent press events; no new visual recipe. */
internal data class ModuleJoinedPressIndication(
    val indication: IndicationNodeFactory,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        indication.create(ModuleJoinedPressInteractionSource(interactionSource))
}
