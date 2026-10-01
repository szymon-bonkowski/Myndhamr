package io.github.szymonbonkowski.myndhamr.domain

/** Clock identity is evidence. ARCore Frame.timestamp is not its Android camera timestamp. */
enum class ClockDomain {
    ANDROID_ELAPSED_REALTIME,
    CAMERA_REALTIME,
    CAMERA_UNKNOWN,
    ARCORE_FRAME,
    APPLICATION_ELAPSED,
}

enum class TimestampUnit { NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS }

sealed interface ClockTimestamp {
    val nanoseconds: Long
    val clockDomain: ClockDomain
}

/** The unmodified sensor value and callback arrival on Android elapsed realtime. */
data class SourceTimestamp(
    override val nanoseconds: Long,
    override val clockDomain: ClockDomain,
    val arrivalElapsedRealtimeNanoseconds: Long,
) : ClockTimestamp {
    init {
        require(nanoseconds >= 0) { "Source time must be nonnegative nanoseconds" }
        require(arrivalElapsedRealtimeNanoseconds >= 0) { "Arrival time must be nonnegative nanoseconds" }
    }

    companion object {
        /** No heuristic unit detection or silent conversion at an ingestion boundary. */
        fun measured(
            value: Long,
            unit: TimestampUnit,
            clockDomain: ClockDomain,
            arrivalElapsedRealtimeNanoseconds: Long,
        ): SourceTimestamp {
            require(unit == TimestampUnit.NANOSECONDS) { "Capture timestamps must be supplied in nanoseconds" }
            return SourceTimestamp(value, clockDomain, arrivalElapsedRealtimeNanoseconds)
        }
    }
}

enum class ClockMappingKind { KNOWN, ESTIMATED }

/** t_target = t_source + offsetNanoseconds. A mapping never changes the source record. */
data class ClockMapping(
    val source: ClockDomain,
    val target: ClockDomain,
    val offsetNanoseconds: Long,
    val uncertaintyNanoseconds: Long,
    val kind: ClockMappingKind,
) {
    init {
        require(uncertaintyNanoseconds >= 0) { "Clock uncertainty must be nonnegative" }
        require(target != ClockDomain.CAMERA_UNKNOWN) { "An unidentified clock is not a normalized target" }
        if (kind == ClockMappingKind.KNOWN) {
            require(source != ClockDomain.CAMERA_UNKNOWN) { "Unknown camera clocks require an estimated mapping" }
            require(uncertaintyNanoseconds == 0L) { "A known clock mapping must have zero uncertainty" }
        }
        if (source == target) {
            require(offsetNanoseconds == 0L && uncertaintyNanoseconds == 0L && kind == ClockMappingKind.KNOWN) {
                "Same-domain mapping must be an identified, known identity"
            }
        }
    }

    fun map(timestamp: SourceTimestamp): MappedTimestamp {
        require(timestamp.clockDomain == source) { "Source clock does not match the clock mapping" }
        val mapped = checkedAddNanoseconds(timestamp.nanoseconds, offsetNanoseconds)
        require(mapped >= 0) { "Clock mapping produced a negative time" }
        return MappedTimestamp(timestamp, this, mapped)
    }
}

class MappedTimestamp internal constructor(
    val original: SourceTimestamp,
    val mapping: ClockMapping,
    override val nanoseconds: Long,
) : ClockTimestamp {
    init {
        require(original.clockDomain == mapping.source && nanoseconds >= 0)
        require(nanoseconds == checkedAddNanoseconds(original.nanoseconds, mapping.offsetNanoseconds))
    }
    override val clockDomain: ClockDomain get() = mapping.target
    val uncertaintyNanoseconds: Long get() = mapping.uncertaintyNanoseconds
}

fun checkedAddNanoseconds(value: Long, offset: Long): Long {
    require(!(offset > 0 && value > Long.MAX_VALUE - offset) &&
        !(offset < 0 && value < Long.MIN_VALUE - offset)) { "Nanosecond addition overflow" }
    return value + offset
}

fun checkedSubtractNanoseconds(left: Long, right: Long): Long {
    require(!(right > 0 && left < Long.MIN_VALUE + right) &&
        !(right < 0 && left > Long.MAX_VALUE + right)) { "Nanosecond subtraction overflow" }
    return left - right
}

/** Callers must apply an explicit mapping before comparing different source clocks. */
fun timestampResidualNanoseconds(reference: ClockTimestamp, sample: ClockTimestamp): Long {
    require(reference.clockDomain == sample.clockDomain) { "Cannot subtract different clock domains" }
    return checkedSubtractNanoseconds(sample.nanoseconds, reference.nanoseconds)
}

data class Timestamped<T>(val timestamp: ClockTimestamp, val value: T)

data class FrameMatch<A, B>(
    val reference: Timestamped<A>,
    val candidate: Timestamped<B>,
    /** candidate time minus reference time, in their common identified source clock. */
    val residualNanoseconds: Long,
)

enum class AssociationFailure { CLOCK_DOMAIN_MISMATCH, DUPLICATE_TIMESTAMP, NONMONOTONIC_TIMESTAMP }
enum class AssociationStream { REFERENCES, CANDIDATES }

sealed class FrameAssociation<out A, out B> {
    data class Matched<A, B>(
        val matches: List<FrameMatch<A, B>>,
        val droppedReferences: List<Timestamped<A>>,
        val droppedCandidates: List<Timestamped<B>>,
    ) : FrameAssociation<A, B>()

    data class Rejected(
        val reason: AssociationFailure,
        val stream: AssociationStream,
        val index: Int,
    ) : FrameAssociation<Nothing, Nothing>()
}

/** Exact equality in the same source clock; no nearest-pose fallback. */
object ExactFrameMatcher {
    fun <A, B> match(
        references: List<Timestamped<A>>,
        candidates: List<Timestamped<B>>,
    ): FrameAssociation<A, B> = matchFrames(references, candidates, 0)
}

/**
 * Chronological greedy one-to-one association. Ties choose the earlier candidate.
 * Consuming a candidate also retires all older unmatched candidates as explicit drops,
 * so the paired candidate sequence cannot reverse time.
 */
class NearestFrameMatcher(val maximumResidualNanoseconds: Long) {
    init {
        require(maximumResidualNanoseconds >= 0) { "Association bound must be nonnegative nanoseconds" }
    }

    fun <A, B> match(
        references: List<Timestamped<A>>,
        candidates: List<Timestamped<B>>,
    ): FrameAssociation<A, B> = matchFrames(references, candidates, maximumResidualNanoseconds)
}

private fun <A, B> matchFrames(
    references: List<Timestamped<A>>,
    candidates: List<Timestamped<B>>,
    bound: Long,
): FrameAssociation<A, B> {
    val domain = references.firstOrNull()?.timestamp?.clockDomain ?: candidates.firstOrNull()?.timestamp?.clockDomain
    validateSequence(references, domain, AssociationStream.REFERENCES)?.let { return it }
    validateSequence(candidates, domain, AssociationStream.CANDIDATES)?.let { return it }
    val matches = mutableListOf<FrameMatch<A, B>>()
    val droppedReferences = mutableListOf<Timestamped<A>>()
    val used = BooleanArray(candidates.size)
    var next = 0
    for (reference in references) {
        if (next == candidates.size) {
            droppedReferences += reference
            continue
        }
        while (next + 1 < candidates.size && candidates[next + 1].timestamp.nanoseconds <= reference.timestamp.nanoseconds) {
            next++
        }
        var selected = next
        var residual = timestampResidualNanoseconds(reference.timestamp, candidates[selected].timestamp)
        if (selected + 1 < candidates.size) {
            val laterResidual = timestampResidualNanoseconds(reference.timestamp, candidates[selected + 1].timestamp)
            if (distance(laterResidual) < distance(residual)) {
                selected++
                residual = laterResidual
            }
        }
        if (distance(residual) <= bound) {
            used[selected] = true
            matches += FrameMatch(reference, candidates[selected], residual)
            next = selected + 1
        } else {
            droppedReferences += reference
        }
    }
    return FrameAssociation.Matched(matches, droppedReferences, candidates.filterIndexed { index, _ -> !used[index] })
}

private fun <T> validateSequence(
    samples: List<Timestamped<T>>,
    domain: ClockDomain?,
    stream: AssociationStream,
): FrameAssociation.Rejected? {
    var previous: Long? = null
    for ((index, sample) in samples.withIndex()) {
        if (sample.timestamp.clockDomain != domain) {
            return FrameAssociation.Rejected(AssociationFailure.CLOCK_DOMAIN_MISMATCH, stream, index)
        }
        if (previous != null && sample.timestamp.nanoseconds <= previous) {
            val reason = if (sample.timestamp.nanoseconds == previous) AssociationFailure.DUPLICATE_TIMESTAMP
            else AssociationFailure.NONMONOTONIC_TIMESTAMP
            return FrameAssociation.Rejected(reason, stream, index)
        }
        previous = sample.timestamp.nanoseconds
    }
    return null
}

// Valid SourceTimestamp/MappedTimestamp values are nonnegative, so their residual cannot be Long.MIN_VALUE.
private fun distance(residual: Long): Long = if (residual < 0) -residual else residual
