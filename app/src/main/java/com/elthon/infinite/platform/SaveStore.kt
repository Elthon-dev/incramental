package com.elthon.infinite.platform

import android.content.Context
import android.util.Log
import com.elthon.infinite.core.GameSave
import com.elthon.infinite.core.SaveCodec
import com.elthon.infinite.core.SaveException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SaveStore(context: Context) {
    private val target = File(context.filesDir, FILE_NAME)
    private val temporary = File(context.filesDir, "$FILE_NAME.tmp")
    private val backup = File(context.filesDir, "$FILE_NAME.bak")
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val worker = Thread({ drainForever() }, "infinite-save-writer").apply {
        isDaemon = true
        start()
    }

    fun load(): GameSave? {
        for (candidate in listOf(target, backup, temporary)) {
            if (!candidate.exists() || candidate.length() == 0L) continue
            val bytes = try {
                candidate.readBytes()
            } catch (error: Exception) {
                Log.w(TAG, "save unreadable from ${candidate.name}", error)
                null
            } ?: continue
            try {
                return SaveCodec.decode(bytes)
            } catch (error: SaveException) {
                Log.w(TAG, "save rejected from ${candidate.name}: ${error.message}")
            } catch (error: Exception) {
                Log.w(TAG, "save invalid from ${candidate.name}", error)
            }
        }
        return null
    }

    fun saveAsync(save: GameSave) {
        val bytes = try {
            SaveCodec.encode(save)
        } catch (error: Exception) {
            Log.w(TAG, "save encoding failed", error)
            return
        }
        queue.offer(bytes)
    }

    fun flushBlocking(save: GameSave, timeoutMillis: Long = 2_000L): Boolean {
        val bytes = try {
            SaveCodec.encode(save)
        } catch (error: Exception) {
            Log.w(TAG, "save encoding failed", error)
            return false
        }
        queue.offer(bytes)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty()) return true
            Thread.sleep(10L)
        }
        return queue.isEmpty()
    }

    fun pendingWrites(): Int = queue.size

    fun shutdown() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (queue.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10L)
        worker.interrupt()
    }

    private fun drainForever() {
        while (!Thread.currentThread().isInterrupted) {
            val bytes = try {
                queue.take()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            writeAtomically(bytes)
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        try {
            temporary.writeBytes(bytes)
            if (target.exists() && !target.renameTo(backup)) backup.delete()
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: Exception) {
            Log.w(TAG, "save write failed", error)
        }
    }

    private companion object {
        const val FILE_NAME = "infinite.save"
        const val TAG = "InfiniteSave"
    }
}
