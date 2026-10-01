package io.github.szymonbonkowski.myndhamr.reconstruction

import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/** Kotlin owns project validation/orchestration; isolated native engines own reconstruction. */
object SparseCommand {
    private val commands = setOf("ingest", "reconstruct")
    fun handles(command: String?) = command in commands

    fun run(args: List<String>, stdout: PrintStream, stderr: PrintStream): Int {
        var output: File? = null
        var stage = "arguments"
        val times = linkedMapOf<String, Double>()
        try {
            require(args.size >= 3 && handles(args[0])) { "Usage: myndhamr ${args.firstOrNull()} <scan> <new-run-directory> [--python path] [--timeout-seconds 600] [--pose-threshold-meters 0.05]" }
            require((args.size-3)%2 == 0) { "Every sparse option requires a value" }
            val options = args.drop(3).chunked(2).associate { it[0] to it[1] }
            require(options.keys.all { it in setOf("--python", "--timeout-seconds", "--pose-threshold-meters", "--repository", "--temporal-window", "--loop-radius-meters", "--max-loop-neighbors", "--view-angle-degrees", "--init-min-tri-angle-degrees") }) { "Unknown sparse option" }
            require(options.size == (args.size-3)/2) { "Duplicate sparse option" }
            val repository = File(options["--repository"] ?: System.getProperty("myndhamr.repository", ".")).canonicalFile
            val python = File(options["--python"] ?: System.getenv("MYNDHAMR_SPARSE_PYTHON") ?: File(repository,"build/v02-venv/bin/python").path).absoluteFile
            val timeout = (options["--timeout-seconds"] ?: "600").toLong().also { require(it in 1..86400) { "Invalid process timeout" } }
            val threshold = (options["--pose-threshold-meters"] ?: "0.05").toDouble().also { require(it.isFinite() && it > 0) { "Invalid pose threshold" } }
            val source = File(args[1]).canonicalFile
            val destination = File(args[2]).canonicalFile
            require(!destination.exists()) { "Run destination already exists: $destination" }
            require(destination.toPath() != source.toPath() && !destination.toPath().startsWith(source.toPath())) { "Derived output must be outside raw scan: $destination" }
            stage = "ingest"
            val started = System.nanoTime()
            val input = Ingest.prepare(source, destination)
            output = destination
            times["ingest"] = (System.nanoTime()-started)/1e9
            val pairStart = System.nanoTime()
            val graphConfig = PairGraphConfig(
                temporalWindow = (options["--temporal-window"] ?: "5").toInt(),
                spatialRadiusMeters = (options["--loop-radius-meters"] ?: "2.0").toDouble(),
                maxLoopNeighbors = (options["--max-loop-neighbors"] ?: "12").toInt(),
                maxViewAngleDegrees = (options["--view-angle-degrees"] ?: "75.0").toDouble())
            val graph = PairGraphs.build(input.frames, graphConfig)
            File(destination,"pairs.json").writeText(reconstructionJson(mapOf("schemaVersion" to 1,"pairs" to graph.pairs.map { mapOf("first" to it.first,"second" to it.second,"reason" to it.reason) },"statistics" to graph.statistics))+"\n")
            times["pair_graph"] = (System.nanoTime()-pairStart)/1e9
            val config = mapOf("schemaVersion" to 1,"poseThresholdMeters" to threshold,"minimumPriorInlierFraction" to .6,
                "minimumMetricBaselineMeters" to .05,"maximumMedianOrientationResidualDegrees" to 20.0,"timeoutSeconds" to timeout)
            File(destination,"metric-config.json").writeText(reconstructionJson(config)+"\n")
            val workerConfig = mapOf("threads" to 4,"maxImageSize" to 3200,"maxFeatures" to 8192,"minNumMatches" to 15,
                "initMinNumInliers" to 30,"initMinTriAngleDegrees" to (options["--init-min-tri-angle-degrees"] ?: "4.0").toDouble(),"absPoseMinNumInliers" to 15,"seed" to 0)
            File(destination,"worker-config.json").writeText(reconstructionJson(workerConfig)+"\n")
            File(destination,"orchestration.json").writeText(reconstructionJson(mapOf("schemaVersion" to 1,"pipeline" to "v0.2",
                "input" to source.path,"python" to python.path,"repository" to repository.path,"stageRuntimeSeconds" to times))+"\n")
            if (args[0] == "ingest") {
                stdout.println(reconstructionJson(mapOf("status" to "ingested","projectId" to input.projectId,"eligibleImages" to input.frames.size,
                    "excludedImages" to input.excluded.map { mapOf("frameId" to it.frameId.toString(),"reason" to it.reason) },"pairGraph" to graph.statistics,"output" to destination.path)))
                return 0
            }
            require(input.frames.size >= 3) { "Sparse reconstruction requires at least three eligible keyframes; found ${input.frames.size}" }
            require(python.isFile && python.canExecute()) { "Pinned sparse Python runtime missing: $python; install desktop-workers/colmap-adapter/requirements.txt" }
            val adapter = File(repository,"desktop-workers/colmap-adapter/worker.py")
            val exporter = File(repository,"desktop-workers/reconstruction-core/metric_output.py")
            val align = File(repository,"build/native/myndhamr_align")
            require(adapter.isFile && exporter.isFile && align.canExecute()) { "Sparse tools missing; run nativeBuild and check --repository" }
            stage = "colmap"
            runProcess(listOf(python.path,adapter.path,destination.path),File(destination,"colmap.log"),timeout)
            stage = "metric_alignment"
            runProcess(listOf(python.path,exporter.path,destination.path,align.path),File(destination,"metric-export.log"),timeout)
            stdout.println(File(destination,"diagnostics.json").readText())
            return 0
        } catch (e: Exception) {
            val failed = mapOf("schemaVersion" to 1,"status" to "failed","stage" to stage,"error" to (e.message ?: e.javaClass.simpleName),"stageRuntimeSeconds" to times)
            output?.let { root ->
                File(root,"orchestration-failure.json").writeText(reconstructionJson(failed)+"\n")
                writeFailureDiagnostics(root, failed)
            }
            stderr.println(reconstructionJson(failed))
            return 2
        }
    }

    internal fun writeFailureDiagnostics(root: File, failed: Map<String, Any>) {
        val target=File(root,"diagnostics.json")
        val document=LinkedHashMap(failed)
        // A process can write success (or partial JSON) before timing out. Keep
        // the exact exporter evidence separately, but always fail the overall run.
        if(target.isFile) {
            val evidence=File(root,"metric-diagnostics-before-failure.json")
            target.copyTo(evidence,overwrite=true)
            document["metricDiagnosticsPath"]=evidence.name
        }
        val adapter=File(root,"adapter-diagnostics.json")
        if(adapter.isFile) document["adapterDiagnosticsPath"]=adapter.name
        target.writeText(reconstructionJson(document)+"\n")
    }

    internal fun runProcess(command: List<String>, log: File, timeoutSeconds: Long) {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["OPENBLAS_NUM_THREADS"] = "1"
            environment()["OMP_NUM_THREADS"] = "4"
        }.start()
        try {
            if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)) {
                error("Sparse process timed out after ${timeoutSeconds}s; log: $log")
            }
            check(process.exitValue() == 0) { "Sparse process exited ${process.exitValue()}; log: $log" }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            // Capture descendants before their parent exits. Terminate children even if
            // the parent responds to TERM before a child that ignores it.
            val interrupted = Thread.interrupted()
            try {
                val children = process.descendants().toList()
                children.forEach { it.destroy() }
                if(process.isAlive) process.destroy()
                process.waitFor(250, TimeUnit.MILLISECONDS)
                children.forEach { if(it.isAlive) it.destroyForcibly() }
                if(process.isAlive) process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            } finally {
                if(interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
