package com.fengeon.resprite_desktop.commands

import com.fengeon.resprite_desktop.model.Point

/**
 * Moves a cell's content by shifting its bounds origin. The raster itself is
 * untouched: the compositor draws the same pixels at the new origin, which is
 * exactly how the real app's SetCellPosCmd behaves.
 *
 * Revert restores the original origin without touching pixels, so it's cheap
 * and exact.
 */
class MoveCellCmd(
    private val cellId: String,
    private val deltaX: Int,
    private val deltaY: Int
) : Command {
    override val label: String = "Move cell"

    private var oldOrigin: Point? = null
    private var layerIdx: Int = -1
    private var cellIdx: Int = -1

    override fun apply(state: EditorState) {
        val found = findCell(state, cellId) ?: return
        layerIdx = found.first
        cellIdx = found.second
        val cell = state.document.layerDatas[layerIdx].cells[cellIdx]
        oldOrigin = cell.bounds.origin
        val newOrigin = Point(
            cell.bounds.origin.x + deltaX,
            cell.bounds.origin.y + deltaY
        )
        state.mutateLayer(layerIdx) { layer ->
            val cells = layer.cells.toMutableList()
            cells[cellIdx] = cells[cellIdx].copy(
                bounds = cells[cellIdx].bounds.copy(origin = newOrigin)
            )
            layer.copy(cells = cells)
        }
    }

    override fun revert(state: EditorState) {
        if (layerIdx < 0 || cellIdx < 0) return
        val o = oldOrigin ?: return
        state.mutateLayer(layerIdx) { layer ->
            val cells = layer.cells.toMutableList()
            if (cellIdx in cells.indices) {
                cells[cellIdx] = cells[cellIdx].copy(
                    bounds = cells[cellIdx].bounds.copy(origin = o)
                )
            }
            layer.copy(cells = cells)
        }
    }

    private fun findCell(state: EditorState, id: String): Pair<Int, Int>? {
        state.document.layerDatas.forEachIndexed { li, layer ->
            layer.cells.forEachIndexed { ci, cell ->
                if (cell.id == id) return li to ci
            }
        }
        return null
    }
}
