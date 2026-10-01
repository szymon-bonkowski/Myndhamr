package io.github.szymonbonkowski.myndhamr.capture

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One FIFO writer with bounded waiting tasks and payload bytes (including the running task).
 * Four waiting slots are unavailable to producers; ordinary controls leave the final slot free.
 * The recorder owns its session gate: it stops admitting samples before submitting finalization.
 * A queue can serve several sessions until shutdown, which drains all previously accepted work.
 */
class CaptureWorkQueue(
    private val onFailure: (String) -> Unit,
    val capacity: Int = 4096,
    val byteBudget: Long = 32L * 1024 * 1024,
) {
    private data class Work(val bytes: Long, val terminal: Boolean, val task: () -> Unit)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val waiting = ArrayDeque<Work>()
    private var payloadBytes = 0L
    private var maximumWaiting = 0
    private var terminalPending = false
    private var shutdownRequested = false
    private var terminated = false
    private var writer: Thread? = null

    init {
        require(capacity > CONTROL_RESERVE) { "Queue capacity must leave at least one producer slot and four control slots" }
        require(byteBudget > 0) { "Payload byte budget must be positive" }
    }

    val queueSize: Int get() = lock.withLock { waiting.size }
    val highWater: Int get() = lock.withLock { maximumWaiting }
    val queuedBytes: Long get() = lock.withLock { payloadBytes }
    val isTerminated: Boolean get() = lock.withLock { terminated }

    /** Rejects before allocating a queue slot or charging any bytes. */
    fun offer(bytes: Long = 0, task: () -> Unit): Boolean = offerInternal(bytes, true, task)

    /** Best-effort diagnostics use producer capacity and drop quietly when it is unavailable. */
    fun offerOptional(task: () -> Unit): Boolean = offerInternal(0, false, task)

    private fun offerInternal(bytes: Long, reportRejection: Boolean, task: () -> Unit): Boolean {
        require(bytes >= 0) { "Payload byte count must be nonnegative" }
        var failure: String? = null
        val accepted = lock.withLock {
            when {
                shutdownRequested -> false
                waiting.size >= capacity - CONTROL_RESERVE -> {
                    failure = "CAPTURE_QUEUE_OVERFLOW"
                    false
                }
                bytes > byteBudget - payloadBytes -> {
                    failure = "CAPTURE_QUEUE_BYTE_BUDGET_EXCEEDED"
                    false
                }
                else -> {
                    payloadBytes += bytes // Admission subtracts first, avoiding signed integer overflow.
                    enqueue(Work(bytes, false, task))
                    true
                }
            }
        }
        if (reportRejection) failure?.let(::reportFailure)
        return accepted
    }

    /** Initialization/export/reopen controls run in the same FIFO, with one slot reserved for finalization. */
    fun executeControl(task: () -> Unit): Boolean {
        var failure: String? = null
        val accepted = lock.withLock {
            when {
                shutdownRequested -> false
                waiting.size >= capacity - 1 -> {
                    failure = "CAPTURE_CONTROL_QUEUE_OVERFLOW"
                    false
                }
                else -> {
                    enqueue(Work(0, false, task))
                    true
                }
            }
        }
        failure?.let(::reportFailure)
        return accepted
    }

    /**
     * Reserved terminal control. Exactly one can be waiting or running at a time.
     * Runs after the already accepted prefix, even if that prefix fails or producers fill their budget.
     * Returns false for a duplicate pending finalizer or shutdown. Does not shut down the queue itself.
     */
    fun finalize(task: () -> Unit): Boolean = lock.withLock {
        if (shutdownRequested || terminalPending) return@withLock false
        check(waiting.size < capacity) { "Terminal queue reservation was consumed" }
        terminalPending = true
        enqueue(Work(0, true, task))
        true
    }

    /** Prevents new admission and wakes the writer. Never discards an accepted task. */
    fun shutdown() = lock.withLock {
        shutdownRequested = true
        if (writer == null) terminated = true
        changed.signalAll()
    }

    /** May be called by the owner after shutdown; the writer never waits for itself. */
    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0) { "Termination timeout must be nonnegative" }
        return lock.withLock {
            if (Thread.currentThread() === writer) return@withLock terminated
            var remaining = unit.toNanos(timeout)
            while (!terminated && remaining > 0) remaining = changed.awaitNanos(remaining)
            terminated
        }
    }

    // Caller holds lock. The lazily created writer cannot dequeue until admission is committed.
    private fun enqueue(work: Work) {
        waiting.addLast(work)
        if (writer == null) {
            try {
                writer = Thread(::drain, "myndhamr-capture-writer-${NEXT_WRITER.incrementAndGet()}")
                writer!!.start()
            } catch (failure: Throwable) {
                writer = null
                waiting.removeLast()
                payloadBytes -= work.bytes
                if (work.terminal) terminalPending = false
                throw failure
            }
        }
        maximumWaiting = maxOf(maximumWaiting, waiting.size)
        changed.signalAll()
    }

    private fun drain() {
        try {
            while (true) {
                val work = lock.withLock {
                    while (waiting.isEmpty() && !shutdownRequested) {
                        try {
                            changed.await()
                        } catch (_: InterruptedException) {
                            // External interruption is not cancellation of immutable accepted evidence.
                        }
                    }
                    if (waiting.isEmpty()) null else waiting.removeFirst()
                } ?: break
                try {
                    work.task()
                } catch (failure: Throwable) {
                    reportFailure("CAPTURE_WORK_FAILED:${failure.message ?: failure.javaClass.simpleName}")
                } finally {
                    lock.withLock {
                        payloadBytes -= work.bytes
                        if (work.terminal) terminalPending = false
                        changed.signalAll()
                    }
                }
            }
        } finally {
            lock.withLock {
                terminated = true
                changed.signalAll()
            }
        }
    }

    private fun reportFailure(message: String) {
        try {
            onFailure(message)
        } catch (_: Throwable) {
            // A UI/diagnostic callback must not kill the writer or lose later accepted measurements.
        }
    }

    private companion object {
        const val CONTROL_RESERVE = 4
        val NEXT_WRITER = AtomicLong()
    }
}
