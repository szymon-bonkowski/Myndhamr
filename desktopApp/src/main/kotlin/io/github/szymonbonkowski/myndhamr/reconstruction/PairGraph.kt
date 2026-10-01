package io.github.szymonbonkowski.myndhamr.reconstruction

import kotlin.math.cos
import kotlin.math.floor

data class PairGraphConfig(
    val temporalWindow: Int = 5,
    val spatialRadiusMeters: Double = 2.0,
    val maxLoopNeighbors: Int = 12,
    val maxViewAngleDegrees: Double = 75.0,
)

data class ImagePair(val first: String, val second: String, val reason: String)

data class PairGraph(val pairs: List<ImagePair>, val statistics: Map<String, Any>)

/** Deterministic sequence plus spatial-loop graph. Spatial work is bounded by sampled cell occupancy. */
object PairGraphs {
    private const val MAX_CELL_REPRESENTATIVES = 32

    fun build(frames: List<ReconstructionFrame>, config: PairGraphConfig = PairGraphConfig()): PairGraph {
        require(config.temporalWindow >= 0) { "temporalWindow must be nonnegative" }
        require(config.spatialRadiusMeters.isFinite() && config.spatialRadiusMeters > 0) { "spatialRadiusMeters must be finite and positive" }
        require(config.maxLoopNeighbors >= 0) { "maxLoopNeighbors must be nonnegative" }
        require(config.maxViewAngleDegrees.isFinite() && config.maxViewAngleDegrees in 0.0..180.0) { "maxViewAngleDegrees must lie in [0,180]" }
        val ordered = frames.sortedBy { it.frameId }
        require(ordered.map { it.frameId }.toSet().size == ordered.size) { "Frame IDs must be unique" }
        val orderedById = ordered.associateBy { it.frameId }

        val edges = sortedMapOf<Pair<Long, Long>, MutableSet<String>>(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        for (index in ordered.indices) {
            val end = minOf(ordered.lastIndex, index + config.temporalWindow)
            for (other in index + 1..end) addReason(edges, ordered[index], ordered[other], "TEMPORAL")
        }

        var candidateChecks = 0L
        var framesWithoutCell = 0L
        var representativeReferences = 0L
        var qualifyingCandidates = 0L
        var sampledOutInsertions = 0L
        var maximumCellOccupancy = 0
        val cellSamples = HashMap<Cell, MutableList<Sample>>()
        val cellOccupancies = HashMap<Cell, Int>()
        val cosineLimit = cos(Math.toRadians(config.maxViewAngleDegrees))
        val neighborhood = (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> (-1..1).map { dz -> Triple(dx, dy, dz) } } }

        if (config.maxLoopNeighbors > 0 && ordered.isNotEmpty()) for (frame in ordered) {
            val cell = cellOf(frame, config.spatialRadiusMeters)
            if (cell == null) {
                framesWithoutCell++
                continue
            }
            val nearby = ArrayList<ReconstructionFrame>(27 * MAX_CELL_REPRESENTATIVES)
            for ((dx, dy, dz) in neighborhood) {
                val samples = cellSamples[Cell(cell.x + dx, cell.y + dy, cell.z + dz)] ?: continue
                representativeReferences += samples.size
                samples.forEach { nearby += it.frame }
            }
            val position = frame.worldFromCamera.translationMeters
            val forward = forward(frame)
            val qualified = ArrayList<Pair<ReconstructionFrame, Double>>()
            for (candidate in nearby) {
                candidateChecks++
                val delta = candidate.worldFromCamera.translationMeters - position
                val distanceSquared = delta.x * delta.x + delta.y * delta.y + delta.z * delta.z
                if (distanceSquared > config.spatialRadiusMeters * config.spatialRadiusMeters) continue
                val otherForward = forward(candidate)
                val dot = (forward.x * otherForward.x + forward.y * otherForward.y + forward.z * otherForward.z).coerceIn(-1.0, 1.0)
                if (dot + 1e-12 < cosineLimit) continue
                qualified += candidate to distanceSquared
            }
            val chosen = qualified.sortedWith(compareBy<Pair<ReconstructionFrame, Double>> { it.second }.thenBy { it.first.frameId })
                .take(config.maxLoopNeighbors)
            qualifyingCandidates += qualified.size.toLong()
            chosen.forEach { (candidate, _) -> addReason(edges, frame, candidate, "SPATIAL_LOOP") }

            val bucket = cellSamples.getOrPut(cell) { mutableListOf() }
            val occupancy = (cellOccupancies[cell] ?: 0) + 1
            cellOccupancies[cell] = occupancy
            maximumCellOccupancy = maxOf(maximumCellOccupancy, occupancy)
            val sample = Sample(frame, stableScore(frame.frameId))
            if (bucket.size < MAX_CELL_REPRESENTATIVES) bucket += sample
            else {
                sampledOutInsertions++
                val worstIndex = bucket.indices.maxWithOrNull { a, b -> sampleComparator.compare(bucket[a], bucket[b]) }!!
                if (sampleComparator.compare(sample, bucket[worstIndex]) < 0) bucket[worstIndex] = sample
            }
        }

        val result = edges.map { (ids, reasons) ->
            val first = orderedById.getValue(ids.first)
            val second = orderedById.getValue(ids.second)
            ImagePair(first.imageName, second.imageName, reasons.sorted().joinToString("+"))
        }
        val stats = linkedMapOf<String, Any>(
            "algorithm" to "sequence-plus-bounded-spatial-hash-v1",
            "inputFrames" to ordered.size,
            "temporalPairs" to edges.values.count { "TEMPORAL" in it },
            "spatialPairs" to edges.values.count { "SPATIAL_LOOP" in it },
            "pairs" to result.size,
            "temporalWindow" to config.temporalWindow,
            "spatialRadiusMeters" to config.spatialRadiusMeters,
            "maxLoopNeighbors" to config.maxLoopNeighbors,
            "maxViewAngleDegrees" to config.maxViewAngleDegrees,
            "maxCellRepresentatives" to MAX_CELL_REPRESENTATIVES,
            "spatialCandidateChecks" to candidateChecks,
            "spatialQualifyingCandidates" to qualifyingCandidates,
            "sampledCellInsertions" to sampledOutInsertions,
            "spatialCells" to cellSamples.size,
            "maximumCellOccupancy" to maximumCellOccupancy,
            "representativesInspected" to representativeReferences,
            "framesOutsideSpatialHashRange" to framesWithoutCell,
        )
        return PairGraph(result, stats)
    }

    private fun addReason(edges: MutableMap<Pair<Long, Long>, MutableSet<String>>, a: ReconstructionFrame, b: ReconstructionFrame, reason: String) {
        if (a.frameId == b.frameId) return
        val ids = if (a.frameId < b.frameId) a.frameId to b.frameId else b.frameId to a.frameId
        edges.getOrPut(ids) { sortedSetOf() } += reason
    }

    private fun cellOf(frame: ReconstructionFrame, radius: Double): Cell? {
        val position = frame.worldFromCamera.translationMeters
        fun coordinate(value: Double): Long? {
            val quotient = floor(value / radius)
            if (!quotient.isFinite() || quotient < Long.MIN_VALUE.toDouble() || quotient > Long.MAX_VALUE.toDouble()) return null
            return quotient.toLong()
        }
        val x = coordinate(position.x) ?: return null
        val y = coordinate(position.y) ?: return null
        val z = coordinate(position.z) ?: return null
        if (x == Long.MIN_VALUE || x == Long.MAX_VALUE || y == Long.MIN_VALUE || y == Long.MAX_VALUE || z == Long.MIN_VALUE || z == Long.MAX_VALUE) return null
        return Cell(x, y, z)
    }

    /** Camera forward is world -R[:,2], matching T_world_camera's -Z forward convention. */
    private fun forward(frame: ReconstructionFrame): Vector {
        val m = frame.worldFromCamera.toColumnMajor()
        return Vector(-m[8], -m[9], -m[10])
    }

    private fun stableScore(value: Long): Long {
        var z = value + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    private val sampleComparator = Comparator<Sample> { first, second ->
        val score = first.score.compareTo(second.score)
        if (score != 0) score else first.frame.frameId.compareTo(second.frame.frameId)
    }

    private data class Cell(val x: Long, val y: Long, val z: Long)
    private data class Sample(val frame: ReconstructionFrame, val score: Long)
    private data class Vector(val x: Double, val y: Double, val z: Double)
}
