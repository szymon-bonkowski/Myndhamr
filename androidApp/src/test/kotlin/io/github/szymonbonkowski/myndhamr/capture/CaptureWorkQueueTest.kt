package io.github.szymonbonkowski.myndhamr.capture

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureWorkQueueTest {
    private fun await(latch: CountDownLatch) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Writer did not reach the expected barrier")
    }

    private fun close(queue: CaptureWorkQueue, release: CountDownLatch? = null) {
        release?.countDown()
        queue.shutdown()
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS), "Capture writer did not terminate")
    }

    @Test
    fun fullProducerAndControlQueuesStillFinalizeExactlyOnceAfterTheAcceptedPrefix() {
        val failures = CopyOnWriteArrayList<String>()
        val queue = CaptureWorkQueue(failures::add, capacity = 12, byteBudget = 64)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = mutableListOf<Int>()
        val finalized = AtomicInteger()
        try {
            assertTrue(queue.offer(8) { entered.countDown(); await(release); observed += 0 })
            await(entered)
            for (id in 1..8) assertTrue(queue.offer(7) { observed += id })
            assertEquals(8, queue.queueSize)
            assertEquals(64, queue.queuedBytes)
            assertFalse(queue.offer { observed += -1 })
            for (id in 9..11) assertTrue(queue.executeControl { observed += id })
            assertEquals(11, queue.queueSize)
            assertFalse(queue.executeControl { observed += -2 })
            assertTrue(queue.finalize {
                observed += 12
                finalized.incrementAndGet()
                queue.shutdown()
            })
            assertFalse(queue.finalize { finalized.incrementAndGet() })
            assertEquals(12, queue.queueSize)
            assertEquals(12, queue.highWater)
            release.countDown()
            assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals((0..12).toList(), observed)
            assertEquals(1, finalized.get())
            assertEquals(0, queue.queueSize)
            assertEquals(0, queue.queuedBytes)
            assertEquals(listOf("CAPTURE_QUEUE_OVERFLOW", "CAPTURE_CONTROL_QUEUE_OVERFLOW"), failures.toList())
        } finally {
            close(queue, release)
        }
    }

    @Test
    fun byteBudgetIncludesTheRunningPayloadAndRejectionChargesNoBytes() {
        val failures = CopyOnWriteArrayList<String>()
        val queue = CaptureWorkQueue(failures::add, capacity = 20, byteBudget = 10)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wrote = mutableListOf<Int>()
        try {
            assertTrue(queue.offer(7) { entered.countDown(); await(release); wrote += 1 })
            await(entered)
            assertEquals(0, queue.queueSize)
            assertEquals(7, queue.queuedBytes)
            assertTrue(queue.offer(3) { wrote += 2 })
            assertEquals(10, queue.queuedBytes)
            assertFalse(queue.offer(1) { wrote += -1 })
            assertEquals(10, queue.queuedBytes)
            assertEquals(1, queue.queueSize)
            assertTrue(queue.finalize { wrote += 3 })
            close(queue, release)
            assertEquals(listOf(1, 2, 3), wrote)
            assertEquals(0, queue.queuedBytes)
            assertEquals(listOf("CAPTURE_QUEUE_BYTE_BUDGET_EXCEEDED"), failures.toList())
        } finally {
            close(queue, release)
        }
    }

    @Test
    fun optionalStatusUsesProducerBudgetAndDropsSilentlyWithoutConsumingFinalizerReserve() {
        val failures = CopyOnWriteArrayList<String>()
        val queue = CaptureWorkQueue(failures::add, capacity = 5)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<String>()
        try {
            assertTrue(queue.offer { entered.countDown(); await(release) })
            await(entered)
            assertTrue(queue.offerOptional { written += "status" })
            assertFalse(queue.offerOptional { written += "dropped" })
            assertEquals(1, queue.queueSize)
            assertEquals(0, queue.queuedBytes)
            assertTrue(queue.finalize { written += "finalized" })
            close(queue, release)
            assertEquals(listOf("status", "finalized"), written)
            assertTrue(failures.isEmpty())
            assertFalse(queue.offerOptional { written += "after shutdown" })
            assertTrue(failures.isEmpty())
        } finally {
            close(queue, release)
        }
    }

    @Test
    fun byteAdmissionSubtractsBeforeAddingAndCannotWrapSignedLong() {
        val failures = CopyOnWriteArrayList<String>()
        val queue = CaptureWorkQueue(failures::add, capacity = 20, byteBudget = Long.MAX_VALUE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(queue.offer(Long.MAX_VALUE - 2) { entered.countDown(); await(release) })
            await(entered)
            assertFalse(queue.offer(3) {})
            assertEquals(Long.MAX_VALUE - 2, queue.queuedBytes)
            assertTrue(queue.offer(2) {})
            assertEquals(Long.MAX_VALUE, queue.queuedBytes)
            assertFalse(queue.offer(Long.MAX_VALUE) {})
            assertEquals(Long.MAX_VALUE, queue.queuedBytes)
            close(queue, release)
            assertEquals(0, queue.queuedBytes)
            assertEquals(2, failures.size)
            assertTrue(failures.all { it == "CAPTURE_QUEUE_BYTE_BUDGET_EXCEEDED" })
        } finally {
            close(queue, release)
        }
    }

    @Test
    fun taskFailureReleasesBytesAndPreservesTheRemainingAcceptedPrefixAndFinalizer() {
        val failures = CopyOnWriteArrayList<String>()
        val queue = CaptureWorkQueue({ failures += it; throw IllegalStateException("notification failed") }, capacity = 20, byteBudget = 10)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = mutableListOf<Int>()
        try {
            assertTrue(queue.offer(4) { entered.countDown(); await(release); observed += 1 })
            await(entered)
            assertTrue(queue.offer(3) { observed += 2; throw IllegalStateException("disk failed") })
            assertTrue(queue.offer(3) { observed += 3 })
            assertTrue(queue.finalize { observed += 4 })
            close(queue, release)
            assertEquals(listOf(1, 2, 3, 4), observed)
            assertEquals(listOf("CAPTURE_WORK_FAILED:disk failed"), failures.toList())
            assertEquals("CAPTURE_WORK_FAILED:disk failed", queue.lastFailure)
            assertEquals(1, queue.failureCallbackErrors)
            assertEquals(0, queue.queuedBytes)
            assertEquals(0, queue.queueSize)
        } finally {
            close(queue, release)
        }
    }

    @Test
    fun failingFailureCallbackCannotKillWriterAndTerminalReservationIsReleasedAfterFailure() {
        val queue = CaptureWorkQueue({ throw IllegalStateException("diagnostic unavailable") }, capacity = 12)
        val first = CountDownLatch(1)
        val afterFirst = CountDownLatch(1)
        val second = CountDownLatch(1)
        try {
            assertTrue(queue.finalize { first.countDown(); throw AssertionError("finalizer failed") })
            assertTrue(queue.executeControl { afterFirst.countDown() })
            await(first)
            await(afterFirst) // Also proves the first finalizer's reservation has been released.
            assertTrue(queue.finalize { second.countDown() })
            await(second)
            close(queue)
            assertEquals(0, queue.queuedBytes)
            assertEquals("CAPTURE_WORK_FAILED:finalizer failed", queue.lastFailure)
            assertEquals(1, queue.failureCallbackErrors)
        } finally {
            close(queue)
        }
    }

    @Test
    fun failureCallbackCanRequestReservedFinalizationWithoutDeadlock() {
        val observed = mutableListOf<String>()
        val finished = CountDownLatch(1)
        val queueReference = AtomicReference<CaptureWorkQueue>()
        val queue = CaptureWorkQueue({
            assertTrue(queueReference.get().finalize { observed += "finalized"; finished.countDown() })
        }, capacity = 12)
        queueReference.set(queue)
        try {
            assertTrue(queue.offer(1) { observed += "failed"; throw IllegalStateException("write") })
            await(finished)
            close(queue)
            assertEquals(listOf("failed", "finalized"), observed)
            assertEquals(0, queue.queuedBytes)
        } finally {
            close(queue)
        }
    }

    @Test
    fun shutdownDrainsAcceptedWorkAndRejectsAllSubsequentAdmission() {
        val failures = CopyOnWriteArrayList<String>()
        val queue = CaptureWorkQueue(failures::add, capacity = 12, byteBudget = 3)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = mutableListOf<Int>()
        try {
            assertTrue(queue.offer(1) { entered.countDown(); await(release); observed += 1 })
            await(entered)
            assertTrue(queue.offer(1) { observed += 2 })
            assertTrue(queue.offer(1) { observed += 3 })
            queue.shutdown()
            assertFalse(queue.awaitTermination(0, TimeUnit.SECONDS))
            assertFalse(queue.offer { observed += -1 })
            assertFalse(queue.executeControl { observed += -2 })
            assertFalse(queue.finalize { observed += -3 })
            close(queue, release)
            assertEquals(listOf(1, 2, 3), observed)
            assertEquals(0, queue.queuedBytes)
            assertTrue(queue.isTerminated)
            assertTrue(failures.isEmpty())
        } finally {
            close(queue, release)
        }
    }

    @Test
    fun concurrentProducersCommitOneOrderedPrefixOnOneWriter() {
        val queue = CaptureWorkQueue({ throw AssertionError(it) }, capacity = 512, byteBudget = 512)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startProducers = CountDownLatch(1)
        val accepted = mutableListOf<Int>()
        val written = mutableListOf<Int>()
        val writerThreads = mutableSetOf<Thread>()
        val workers = mutableListOf<Thread>()
        val producerFailures = CopyOnWriteArrayList<Throwable>()
        try {
            assertTrue(queue.offer { entered.countDown(); await(release) })
            await(entered)
            repeat(8) { producer ->
                workers += Thread {
                    try {
                        await(startProducers)
                        repeat(20) { item ->
                            val id = producer * 20 + item
                            synchronized(accepted) {
                                assertTrue(queue.offer(1) { written += id; writerThreads += Thread.currentThread() })
                                accepted += id
                            }
                        }
                    } catch (failure: Throwable) { producerFailures += failure }
                }.apply { start() }
            }
            startProducers.countDown()
            workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
            assertTrue(producerFailures.isEmpty(), producerFailures.toString())
            assertEquals(160, queue.queueSize)
            assertEquals(160, queue.queuedBytes)
            close(queue, release)
            assertEquals(accepted, written)
            assertEquals(160, written.toSet().size)
            assertEquals(1, writerThreads.size)
            writerThreads.single().join(1_000)
            assertFalse(writerThreads.single().isAlive)
        } finally {
            startProducers.countDown()
            close(queue, release)
            workers.forEach { it.join(5_000) }
        }
    }

    @Test
    fun repeatedSessionsAndQueueLifecyclesReleaseBytesAndThreads() {
        repeat(25) {
            val failures = CopyOnWriteArrayList<String>()
            val queue = CaptureWorkQueue(failures::add, capacity = 12, byteBudget = 4)
            val thread = AtomicReference<Thread>()
            val observed = mutableListOf<Int>()
            try {
                repeat(3) { session ->
                    val afterFinalization = CountDownLatch(1)
                    assertTrue(queue.executeControl { observed += session * 3; thread.set(Thread.currentThread()) })
                    assertTrue(queue.offer(4) { observed += session * 3 + 1 })
                    assertTrue(queue.finalize { observed += session * 3 + 2 })
                    assertTrue(queue.executeControl { afterFinalization.countDown() })
                    await(afterFinalization)
                    assertEquals(0, queue.queuedBytes)
                }
                close(queue)
                assertEquals((0..8).toList(), observed)
                assertTrue(failures.isEmpty())
                thread.get().join(1_000)
                assertFalse(thread.get().isAlive)
            } finally {
                close(queue)
            }
        }
    }

    @Test
    fun emptyShutdownCreatesNoWriterAndInvalidBudgetsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CaptureWorkQueue({}, capacity = 4) }
        assertFailsWith<IllegalArgumentException> { CaptureWorkQueue({}, byteBudget = 0) }
        val queue = CaptureWorkQueue({ throw AssertionError(it) })
        try {
            assertFailsWith<IllegalArgumentException> { queue.offer(-1) {} }
            assertEquals(0, queue.queueSize)
            assertEquals(0, queue.queuedBytes)
            assertEquals(0, queue.highWater)
            queue.shutdown()
            assertTrue(queue.isTerminated)
            assertTrue(queue.awaitTermination(0, TimeUnit.SECONDS))
            assertFalse(queue.offer {})
            assertFalse(queue.executeControl {})
            assertFalse(queue.finalize {})
            assertFailsWith<IllegalArgumentException> { queue.awaitTermination(-1, TimeUnit.SECONDS) }
        } finally {
            close(queue)
        }
    }
}
