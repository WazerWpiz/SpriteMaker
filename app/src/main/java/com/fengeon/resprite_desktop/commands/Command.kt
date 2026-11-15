package com.fengeon.resprite_desktop.commands

/**
 * Every user-visible edit is a Command, mirroring the original app's Cmd
 * classes (SpriteFlipCmd, CanvasResizeCmd, CreateLayerCmd, ...). Commands
 * are executed against an EditorState and are reversible.
 */
interface Command {
    val label: String
    fun apply(state: EditorState)
    fun revert(state: EditorState)
}

class UndoManager(private val limit: Int = 200) {

    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun execute(state: EditorState, cmd: Command) {
        cmd.apply(state)
        undoStack.addLast(cmd)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(state: EditorState) {
        val cmd = undoStack.removeLastOrNull() ?: return
        cmd.revert(state)
        redoStack.addLast(cmd)
    }

    fun redo(state: EditorState) {
        val cmd = redoStack.removeLastOrNull() ?: return
        cmd.apply(state)
        undoStack.addLast(cmd)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
