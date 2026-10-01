package io.github.szymonbonkowski.myndhamr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureTimingTest {
    private fun source(ns: Long, domain: ClockDomain = ClockDomain.CAMERA_REALTIME) = SourceTimestamp(ns, domain, ns + 7)
    private fun samples(vararg times: Long, domain: ClockDomain = ClockDomain.CAMERA_REALTIME): List<Timestamped<Int>> =
        times.mapIndexed { index, time -> Timestamped(source(time, domain), index) }

    @Test
    fun timestampUnitsAreExplicitAndWrongUnitsAreRejected() {
        val measured = SourceTimestamp.measured(1_234_567_890, TimestampUnit.NANOSECONDS, ClockDomain.CAMERA_REALTIME, 1_234_567_999)
        assertEquals(1_234_567_890, measured.nanoseconds)
        for (unit in listOf(TimestampUnit.SECONDS, TimestampUnit.MILLISECONDS, TimestampUnit.MICROSECONDS)) {
            assertFailsWith<IllegalArgumentException> {
                SourceTimestamp.measured(1_234, unit, ClockDomain.CAMERA_REALTIME, 1_234_567_999)
            }
        }
        assertFailsWith<IllegalArgumentException> { SourceTimestamp(-1, ClockDomain.CAMERA_REALTIME, 10) }
        assertFailsWith<IllegalArgumentException> { SourceTimestamp(1, ClockDomain.CAMERA_REALTIME, -1) }
    }

    @Test
    fun constantOffsetPreservesOriginalAndRecoveryNeedsTheCorrectSign() {
        val mapping = ClockMapping(ClockDomain.ARCORE_FRAME, ClockDomain.CAMERA_REALTIME, -1_500, 40, ClockMappingKind.ESTIMATED)
        for (cameraTime in listOf(100_000L, 101_000L, 103_000L)) {
            val original = source(cameraTime + 1_500, ClockDomain.ARCORE_FRAME)
            val corrected = mapping.map(original)
            assertSame(original, corrected.original)
            assertEquals(original.nanoseconds, corrected.original.nanoseconds)
            assertEquals(cameraTime, corrected.nanoseconds)
            assertEquals(40, corrected.uncertaintyNanoseconds)
            assertEquals(0, timestampResidualNanoseconds(source(cameraTime), corrected))
            val wrongSign = mapping.copy(offsetNanoseconds = 1_500).map(original)
            assertEquals(3_000, timestampResidualNanoseconds(source(cameraTime), wrongSign))
        }
    }

    @Test
    fun mappingRequiresCorrectSourceAndDoesNotInferArcoreCameraIdentity() {
        val mapping = ClockMapping(ClockDomain.CAMERA_REALTIME, ClockDomain.ANDROID_ELAPSED_REALTIME, 0, 0, ClockMappingKind.KNOWN)
        assertEquals(234_567, mapping.map(source(234_567)).nanoseconds)
        assertFailsWith<IllegalArgumentException> { mapping.map(source(234_567, ClockDomain.ARCORE_FRAME)) }
        assertFailsWith<IllegalArgumentException> { timestampResidualNanoseconds(source(1), source(1, ClockDomain.ARCORE_FRAME)) }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(ClockDomain.CAMERA_REALTIME, ClockDomain.ANDROID_ELAPSED_REALTIME, 0, 1, ClockMappingKind.KNOWN)
        }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(ClockDomain.CAMERA_REALTIME, ClockDomain.CAMERA_REALTIME, 1, 0, ClockMappingKind.KNOWN)
        }
    }

    @Test
    fun unknownClockNeedsMeasuredMappingAndCannotUseAnIdentity() {
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(ClockDomain.CAMERA_UNKNOWN, ClockDomain.CAMERA_UNKNOWN, 0, 0, ClockMappingKind.KNOWN)
        }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(ClockDomain.CAMERA_UNKNOWN, ClockDomain.ANDROID_ELAPSED_REALTIME, 0, 0, ClockMappingKind.KNOWN)
        }
        val estimated = ClockMapping(ClockDomain.CAMERA_UNKNOWN, ClockDomain.ANDROID_ELAPSED_REALTIME, 150, 50, ClockMappingKind.ESTIMATED)
        assertEquals(350, estimated.map(source(200, ClockDomain.CAMERA_UNKNOWN)).nanoseconds)
        assertFailsWith<IllegalArgumentException> { estimated.copy(uncertaintyNanoseconds = -1) }
        val exact = assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match(
            samples(200, domain = ClockDomain.CAMERA_UNKNOWN), samples(200, domain = ClockDomain.CAMERA_UNKNOWN),
        ))
        assertEquals(1, exact.matches.size)
    }

    @Test
    fun integerMappingAndResidualRetainIndividualNanosecondsBeyondDoublePrecision() {
        val epoch = 9_007_199_254_740_992L
        val original = source(epoch + 1, ClockDomain.ARCORE_FRAME)
        val mapped = ClockMapping(ClockDomain.ARCORE_FRAME, ClockDomain.CAMERA_REALTIME, 1, 0, ClockMappingKind.KNOWN).map(original)
        assertEquals(epoch + 2, mapped.nanoseconds)
        assertEquals(1, timestampResidualNanoseconds(source(epoch + 1), mapped))
        val exact = assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match(samples(epoch, epoch + 1), samples(epoch + 1)))
        assertEquals(epoch + 1, exact.matches.single().reference.timestamp.nanoseconds)
        assertEquals(epoch, exact.droppedReferences.single().timestamp.nanoseconds)
    }

    @Test
    fun checkedArithmeticRejectsOverflowAndNegativeMappedTimes() {
        assertEquals(Long.MAX_VALUE, checkedAddNanoseconds(Long.MAX_VALUE - 1, 1))
        assertEquals(Long.MIN_VALUE, checkedAddNanoseconds(Long.MIN_VALUE + 1, -1))
        assertEquals(0, checkedSubtractNanoseconds(Long.MIN_VALUE, Long.MIN_VALUE))
        assertFailsWith<IllegalArgumentException> { checkedAddNanoseconds(Long.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { checkedAddNanoseconds(Long.MIN_VALUE, -1) }
        assertFailsWith<IllegalArgumentException> { checkedSubtractNanoseconds(Long.MAX_VALUE, -1) }
        assertFailsWith<IllegalArgumentException> { checkedSubtractNanoseconds(Long.MIN_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(ClockDomain.ARCORE_FRAME, ClockDomain.CAMERA_REALTIME, 1, 0, ClockMappingKind.KNOWN)
                .map(SourceTimestamp(Long.MAX_VALUE, ClockDomain.ARCORE_FRAME, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            ClockMapping(ClockDomain.ARCORE_FRAME, ClockDomain.CAMERA_REALTIME, -2, 0, ClockMappingKind.KNOWN).map(source(1, ClockDomain.ARCORE_FRAME))
        }
    }

    @Test
    fun exactAssociationReportsAllUnmatchedSamplesWithoutFabricatingPose() {
        val result = assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match(samples(10, 20, 30), samples(9, 20, 31)))
        assertEquals(listOf(20L), result.matches.map { it.reference.timestamp.nanoseconds })
        assertEquals(listOf(0L), result.matches.map { it.residualNanoseconds })
        assertEquals(listOf(10L, 30L), result.droppedReferences.map { it.timestamp.nanoseconds })
        assertEquals(listOf(9L, 31L), result.droppedCandidates.map { it.timestamp.nanoseconds })
    }

    @Test
    fun nearestBoundIsInclusiveAndTiesChooseEarlierWithoutReuse() {
        val result = assertIs<FrameAssociation.Matched<Int, Int>>(NearestFrameMatcher(10).match(samples(100, 101, 150, 200), samples(90, 110, 160, 211)))
        assertEquals(listOf(90L, 110L, 160L), result.matches.map { it.candidate.timestamp.nanoseconds })
        assertEquals(listOf(-10L, 9L, 10L), result.matches.map { it.residualNanoseconds })
        assertEquals(listOf(200L), result.droppedReferences.map { it.timestamp.nanoseconds })
        assertEquals(listOf(211L), result.droppedCandidates.map { it.timestamp.nanoseconds })
        assertEquals(result.matches.size, result.matches.map { it.candidate }.toSet().size)
        assertFailsWith<IllegalArgumentException> { NearestFrameMatcher(-1) }
    }

    @Test
    fun nearestNeverReusesOneCandidateAcrossTwoReferences() {
        val result = assertIs<FrameAssociation.Matched<Int, Int>>(NearestFrameMatcher(1).match(samples(99, 101), samples(100)))
        assertEquals(99, result.matches.single().reference.timestamp.nanoseconds)
        assertEquals(101, result.droppedReferences.single().timestamp.nanoseconds)
        assertTrue(result.droppedCandidates.isEmpty())
    }

    @Test
    fun duplicateOrUnorderedInputIsRejectedWithLocation() {
        for (matcher in listOf<(List<Timestamped<Int>>, List<Timestamped<Int>>) -> FrameAssociation<Int, Int>>(
            ExactFrameMatcher::match, NearestFrameMatcher(10)::match,
        )) {
            assertEquals(FrameAssociation.Rejected(AssociationFailure.DUPLICATE_TIMESTAMP, AssociationStream.REFERENCES, 1),
                matcher(samples(10, 10), samples(10)))
            assertEquals(FrameAssociation.Rejected(AssociationFailure.NONMONOTONIC_TIMESTAMP, AssociationStream.REFERENCES, 1),
                matcher(samples(20, 10), samples(10)))
            assertEquals(FrameAssociation.Rejected(AssociationFailure.DUPLICATE_TIMESTAMP, AssociationStream.CANDIDATES, 1),
                matcher(samples(10), samples(10, 10)))
            assertEquals(FrameAssociation.Rejected(AssociationFailure.NONMONOTONIC_TIMESTAMP, AssociationStream.CANDIDATES, 1),
                matcher(samples(10), samples(20, 10)))
        }
    }

    @Test
    fun everySampleMustUseOneCommonClockIncludingWithinAStream() {
        assertEquals(FrameAssociation.Rejected(AssociationFailure.CLOCK_DOMAIN_MISMATCH, AssociationStream.CANDIDATES, 0),
            ExactFrameMatcher.match(samples(1), samples(1, domain = ClockDomain.ARCORE_FRAME)))
        val mixed = samples(1) + samples(2, domain = ClockDomain.ARCORE_FRAME)
        assertEquals(FrameAssociation.Rejected(AssociationFailure.CLOCK_DOMAIN_MISMATCH, AssociationStream.REFERENCES, 1),
            NearestFrameMatcher(0).match(mixed, samples(1)))
    }

    @Test
    fun mappedSequencesCanBeMatchedOnlyAfterTheExplicitOffset() {
        val mapping = ClockMapping(ClockDomain.ARCORE_FRAME, ClockDomain.CAMERA_REALTIME, -500, 12, ClockMappingKind.ESTIMATED)
        val ar = samples(1500, 2500, domain = ClockDomain.ARCORE_FRAME)
        val result = assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match(
            samples(1000, 2000), ar.map { Timestamped(mapping.map(it.timestamp as SourceTimestamp), it.value) },
        ))
        assertEquals(2, result.matches.size)
        assertEquals(listOf(1500L, 2500L), result.matches.map { (it.candidate.timestamp as MappedTimestamp).original.nanoseconds })
    }

    @Test
    fun missingStreamsProduceExplicitDrops() {
        assertTrue(assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match<Int, Int>(emptyList(), emptyList())).matches.isEmpty())
        assertEquals(2, assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match<Int, Int>(samples(1, 2), emptyList())).droppedReferences.size)
        assertEquals(2, assertIs<FrameAssociation.Matched<Int, Int>>(ExactFrameMatcher.match<Int, Int>(emptyList(), samples(1, 2))).droppedCandidates.size)
    }

    @Test
    fun nearestSelectionAgreesWithIndependentGreedyOracle() {
        // Many different missing-frame patterns; oracle scans all remaining candidates each time.
        for (phase in 0..8) {
            val references = samples(*(0..30).map { it * 19L + phase }.toLongArray())
            val candidates = samples(*(0..30).filter { (it + phase) % 4 != 0 }.map { it * 21L + 3 }.toLongArray())
            val available = candidates.toMutableList()
            val expected = mutableListOf<Pair<Long, Long>>()
            for (reference in references) {
                val best = available.minWithOrNull(compareBy<Timestamped<Int>>(
                    { kotlin.math.abs(it.timestamp.nanoseconds - reference.timestamp.nanoseconds) }, { it.timestamp.nanoseconds },
                ))
                if (best != null && kotlin.math.abs(best.timestamp.nanoseconds - reference.timestamp.nanoseconds) <= 13) {
                    expected += reference.timestamp.nanoseconds to best.timestamp.nanoseconds
                    // Chronological matching cannot consume a candidate older than the last match later.
                    available.removeAll { it.timestamp.nanoseconds <= best.timestamp.nanoseconds }
                }
            }
            val actual = assertIs<FrameAssociation.Matched<Int, Int>>(NearestFrameMatcher(13).match(references, candidates))
            assertEquals(expected, actual.matches.map { it.reference.timestamp.nanoseconds to it.candidate.timestamp.nanoseconds })
            assertEquals(references.size, actual.matches.size + actual.droppedReferences.size)
            assertEquals(candidates.size, actual.matches.size + actual.droppedCandidates.size)
        }
    }
}
