package com.fengeon.resprite_desktop.commands

import com.fengeon.resprite_desktop.model.FrameData

/**
 * Changes one frame's metadata (duration, clip name, clip direction) as a single
 * undoable step. Frames carried no property command before, so animation tools
 * could create frames but never set their timing.
 */
class SetFramePropertyCmd(
    private val index: Int,
    private val transform: (FrameData) -> FrameData
) : Command {

    override val label: String = "Set Frame Property"
    private var previous: FrameData? = null

    override fun apply(state: EditorState) {
        previous = state.document.frameDatas.getOrNull(index) ?: return
        state.mutateFrame(index, transform)
    }

    override fun revert(state: EditorState) {
        previous?.let { state.mutateFrame(index) { _ -> it } }
    }
}

/** Sets the document-wide playback rate (frames per second). */
class SetFrameRateCmd(private val fps: Int) : Command {

    override val label: String = "Set Frame Rate"
    private var previous: Int? = null

    override fun apply(state: EditorState) {
        previous = state.document.frameRate
        state.document = state.document.copy(frameRate = fps.coerceIn(1, 60))
    }

    override fun revert(state: EditorState) {
        previous?.let { state.document = state.document.copy(frameRate = it) }
    }
}
