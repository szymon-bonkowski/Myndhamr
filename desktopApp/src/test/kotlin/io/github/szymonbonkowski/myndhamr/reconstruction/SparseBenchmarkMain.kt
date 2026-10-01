package io.github.szymonbonkowski.myndhamr.reconstruction
import io.github.szymonbonkowski.myndhamr.domain.*
import io.github.szymonbonkowski.myndhamr.scan.v1.CaptureFrame
import java.io.File
import kotlin.math.sin
fun main() {
    val records=listOf(1000,10000).map { count ->
        val frames=(1..count).map { i -> ReconstructionFrame(i.toLong(),i*1_000_000L,"BENCHMARK","frame-$i.png",File("unused"),
            PinholeIntrinsics(640,480,500.0,500.0,320.0,240.0),RigidTransform.translation(Vector3((i%100)*.1,(i/100)*.1,sin(i*.001)*.1)),CaptureFrame.getDefaultInstance()) }
        PairGraphs.build(frames.take(100))
        val before=System.nanoTime();val graph=PairGraphs.build(frames)
        check(graph.pairs.size <= count*17)
        check((graph.statistics["spatialCandidateChecks"] as Long) <= count*27L*32L)
        mapOf("frames" to count,"seconds" to (System.nanoTime()-before)/1e9,"statistics" to graph.statistics)
    }
    println(reconstructionJson(mapOf("benchmark" to "v0.2-bounded-pair-graph","java" to System.getProperty("java.version"),"records" to records)))
}
