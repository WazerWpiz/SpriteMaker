package com.fengeon.resprite_desktop.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fengeon.resprite_desktop.commands.EditorState
import com.fengeon.resprite_desktop.commands.UndoManager
import com.fengeon.resprite_desktop.model.Document
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Single owner of the live document. The Compose screen and the HTTP tool API
 * both go through here so there is exactly one EditorState and one undo stack.
 *
 * Threading: cell bitmaps and the Compose snapshot state are not thread-safe.
 * Every mutation from a background thread (the HTTP server) must run through
 * [onMain]; reads used to build a response do the same.
 */
object RespriteEngine {

    @Volatile
    var state: EditorState = EditorState()
        private set

    val undo: UndoManager = UndoManager()

    /** Bumped after every change so Compose knows to recompose and re-composite. */
    var revision by mutableStateOf(0L)
        private set

    private val main = Handler(Looper.getMainLooper())

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** Signal that the document changed. Must be called on the main thread. */
    fun touch() {
        revision = System.nanoTime()
    }

    /** Swap in a freshly opened document. Must be called on the main thread. */
    fun replaceDocument(document: Document, bitmaps: Map<String, Bitmap>) {
        state = EditorState(document, bitmaps)
        undo.clear()
        touch()
    }

    /**
     * Runs [block] on the UI thread and returns its result. Called from the HTTP
     * thread this blocks until the main thread has finished the work.
     */
    fun <T> onMain(block: () -> T): T {
        if (isMainThread()) return block()
        val result = AtomicReference<T?>()
        val failure = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }
}
