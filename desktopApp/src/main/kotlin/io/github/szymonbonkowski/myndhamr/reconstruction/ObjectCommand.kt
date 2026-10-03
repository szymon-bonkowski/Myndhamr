package io.github.szymonbonkowski.myndhamr.reconstruction

import io.github.szymonbonkowski.myndhamr.store.CaptureProject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.security.MessageDigest

/** Kotlin validates source identity and owns orchestration; the isolated worker owns object stages. */
object ObjectCommand {
    private const val DEFAULT_TIMEOUT_SECONDS = 3600L
    private const val DEFAULT_MAX_IMAGE_SIZE = 1600
    private val optionNames = setOf(
        "--sparse", "--python", "--sparse-python", "--repository", "--timeout-seconds",
        "--max-image-size", "--radii-meters", "--max-edge-meters", "--min-component-faces", "--formats",
    )

    fun handles(command: String?) = command in setOf("object", "dense", "mesh", "export", "mesh-validate")

    fun run(args: List<String>, stdout: PrintStream, stderr: PrintStream): Int {
        var runRoot: File? = null
        var runCandidate: File? = null
        var stage = "arguments"
        val times = linkedMapOf<String, Double>()
        try {
            require(args.isNotEmpty() && handles(args[0])) { "Unsupported object command" }
            val command = args[0]
            val expectedPositionals = if (command == "mesh-validate") 2 else 3
            require(args.size >= expectedPositionals) { usage(command) }
            require((args.size - expectedPositionals) % 2 == 0) { "Every object option requires a value" }
            val optionPairs = args.drop(expectedPositionals).chunked(2)
            val options = optionPairs.associate { it[0] to it[1] }
            require(options.keys.all { it in optionNames }) { "Unknown object option" }
            require(options.size == optionPairs.size) { "Duplicate object option" }
            if (command == "mesh-validate") {
                require(options.keys.all { it in setOf("--python", "--repository") }) {
                    "mesh-validate accepts only --python and --repository"
                }
            }

            val repository = File(options["--repository"] ?: System.getProperty("myndhamr.repository", ".")).canonicalFile
            val densePython = File(options["--python"] ?: File(repository, "build/v03-venv/bin/python").path).absoluteFile
            val sparsePython = File(options["--sparse-python"] ?: File(repository, "build/v02-venv/bin/python").path).absoluteFile
            val timeout = (options["--timeout-seconds"] ?: DEFAULT_TIMEOUT_SECONDS.toString()).toLong()
                .also { require(it in 1..86400) { "Invalid process timeout" } }
            val config = config(options)
            val worker = File(repository, "desktop-workers/object-mesh/worker.py")
            if (command != "object") {
                require("--sparse" !in options && "--sparse-python" !in options) {
                    "--sparse and --sparse-python are only valid for object"
                }
            }

            if (command == "mesh-validate") {
                val objectRun = File(args[1]).canonicalFile
                require(objectRun.isDirectory) { "Object run does not exist: $objectRun" }
                require(File(objectRun, "object-config.json").isFile) { "Not an object run: missing object-config.json in $objectRun" }
                require(File(objectRun, "object-manifest.json").isFile) { "Not an initialized object run: missing object-manifest.json in $objectRun" }
                require(densePython.isFile && densePython.canExecute()) { "Pinned dense Python runtime missing: $densePython" }
                require(worker.isFile) { "Object-mesh worker missing: $worker" }
                runRoot = objectRun
                stage = "validate"
                invoke(worker, densePython, objectRun, objectRun, "validate", timeout, times)
                printDiagnostics(objectRun, stdout)
                return 0
            }

            val sparseCommand = command == "object"
            val sourceOrSparse = File(args[1]).canonicalFile
            val objectRun = File(args[2]).canonicalFile
            val sparseRun: File
            val sourceManifestSha256: String
            if (sparseCommand) {
                sourceManifestSha256 = validatedManifestSha256(sourceOrSparse)
                sparseRun = if (options["--sparse"] != null) {
                    File(options.getValue("--sparse")).canonicalFile.also { require(it.isDirectory) { "Sparse run does not exist: $it" } }
                } else {
                    File(objectRun.parentFile, objectRun.name + "-sparse").canonicalFile
                }
                if (options["--sparse"] == null && sparseRun.exists()) {
                    require(sparseRun.isDirectory) { "Sibling sparse run is not a directory: $sparseRun" }
                }
                requireOutside(sourceOrSparse, objectRun, "Object output must be outside the raw scan")
                requireOutside(sourceOrSparse, sparseRun, "Sparse run must be outside the raw scan")
                requireOutside(sparseRun, objectRun, "Object output must differ from and be outside the sparse run")
                if (options["--sparse"] == null && !sparseRun.exists()) {
                    require(sparsePython.isFile && sparsePython.canExecute()) { "Pinned sparse Python runtime missing: $sparsePython" }
                    stage = "sparse"
                    val capturedOut = ByteArrayOutputStream()
                    val capturedErr = ByteArrayOutputStream()
                    val status = SparseCommand.run(
                        listOf("reconstruct", sourceOrSparse.path, sparseRun.path,
                            "--python", sparsePython.path, "--timeout-seconds", timeout.toString(), "--repository", repository.path),
                        PrintStream(capturedOut), PrintStream(capturedErr),
                    )
                    check(status == 0) {
                        val details = capturedErr.toString().trim().ifBlank { capturedOut.toString().trim().ifBlank { "Sparse reconstruction failed" } }
                        "$details; see sparse run diagnostics at $sparseRun"
                    }
                }
            } else {
                sparseRun = sourceOrSparse
                require(sparseRun.isDirectory) { "Sparse run does not exist: $sparseRun" }
                sourceManifestSha256 = sparseSourceManifestSha256(sparseRun)
                requireOutside(sparseRun, objectRun, "Object output must be outside the sparse run")
            }
            requireOutside(sourceOrSparse, objectRun, "Object output must be outside its input directory")
            require(densePython.isFile && densePython.canExecute()) { "Pinned dense Python runtime missing: $densePython" }
            require(worker.isFile) { "Object-mesh worker missing: $worker" }

            val createdObjectRun = !objectRun.exists()
            if (command in setOf("mesh", "export", "mesh-validate")) {
                require(objectRun.isDirectory) { "Object run does not exist: $objectRun" }
                require(File(objectRun, "object-manifest.json").isFile) { "Object stages are not initialized: $objectRun" }
            } else if (!objectRun.exists()) {
                require(objectRun.mkdirs()) { "Cannot create object run: $objectRun" }
            }
            if (!createdObjectRun && !File(objectRun, "object-manifest.json").isFile) {
                val ownedNames = setOf("object-config.json", "object-worker.log", "object-orchestration-failure.json",
                    "object-all.log", "object-dense.log", "object-mesh.log", "object-export.log", "object-validate.log")
                val unexpected = objectRun.list()?.filterNot { it in ownedNames }.orEmpty()
                require(unexpected.isEmpty()) { "Refusing to adopt nonempty unowned object directory: $objectRun" }
            }
            val document = LinkedHashMap(config)
            document["expectedSourceManifestSha256"] = sourceManifestSha256
            writeStableConfig(objectRun, document)
            runCandidate = objectRun
            if (createdObjectRun || File(objectRun, "object-manifest.json").isFile) runRoot = objectRun

            val workerStage = when (command) {
                "object" -> "all"
                "dense" -> "dense"
                "mesh" -> "mesh"
                "export" -> "export"
                else -> error("Unsupported object stage")
            }
            stage = workerStage
            invoke(worker, densePython, sparseRun, objectRun, workerStage, timeout, times)
            printDiagnostics(objectRun, stdout)
            return 0
        } catch (e: Exception) {
            val failure = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "status" to "failed",
                "stage" to stage,
                "error" to (e.message ?: e.javaClass.simpleName),
                "stageRuntimeSeconds" to times,
            )
            val ownedRun = runRoot ?: runCandidate?.takeIf { File(it, "object-manifest.json").isFile }
            ownedRun?.takeIf { it.isDirectory }?.let { root ->
                try { File(root, "object-orchestration-failure.json").writeText(reconstructionJson(failure) + "\n") }
                catch (writeError: Exception) { stderr.println("Could not write object failure diagnostics: ${writeError.message}") }
            }
            stderr.println(reconstructionJson(failure))
            return 2
        }
    }

    private fun config(options: Map<String, String>): LinkedHashMap<String, Any> {
        val maxImageSize = (options["--max-image-size"] ?: DEFAULT_MAX_IMAGE_SIZE.toString()).toInt()
            .also { require(it in 32..16000) { "--max-image-size must be between 32 and 16000" } }
        val radii = (options["--radii-meters"] ?: "0.005,0.01").split(',').map { token ->
            token.toDouble().also { require(it.isFinite() && it > 0) { "--radii-meters values must be finite and positive" } }
        }.also { require(it.isNotEmpty() && it == it.distinct().sorted()) { "--radii-meters must be a non-empty increasing list of unique values" } }
        val maxEdge = (options["--max-edge-meters"] ?: "0.02").toDouble()
            .also { require(it.isFinite() && it > 0) { "--max-edge-meters must be finite and positive" } }
        val minFaces = (options["--min-component-faces"] ?: "0").toInt()
            .also { require(it >= 0) { "--min-component-faces must be non-negative" } }
        val formats = (options["--formats"] ?: "ply,obj,glb").split(',').map { it.trim().lowercase() }
            .also { require(it.isNotEmpty() && it.all { format -> format in setOf("ply", "obj", "glb") } && it.distinct().size == it.size) { "--formats must be a unique list from ply,obj,glb" } }
        return linkedMapOf(
            "schemaVersion" to 1,
            "maxImageSize" to maxImageSize,
            "radiiMeters" to radii,
            "maxEdgeMeters" to maxEdge,
            "minComponentFaces" to minFaces,
            "formats" to formats,
        )
    }

    private fun validatedManifestSha256(source: File): String {
        require(source.exists()) { "Capture source does not exist: $source" }
        val temp = if (source.isDirectory) null else Files.createTempDirectory("myndhamr-object-ingest-").toFile()
        val root = if (source.isDirectory) source else File(temp, "project")
        try {
            if (!source.isDirectory) {
                val report = CaptureProject.importPackage(source, root)
                require(report.valid) { "Invalid capture package: ${report.diagnostics.joinToString("; ")}" }
            }
            val report = CaptureProject.validate(root)
            require(report.valid) { "Invalid capture project: ${report.diagnostics.joinToString("; ").ifBlank { "validation failed without diagnostics" }}" }
            return sha256(File(root, CaptureProject.MANIFEST))
        } finally {
            temp?.deleteRecursively()
        }
    }

    private fun sparseSourceManifestSha256(sparseRun: File): String {
        val diagnostics = File(sparseRun, "diagnostics.json")
        require(diagnostics.isFile) { "Sparse diagnostics missing: $diagnostics" }
        val match = Regex("\\\"sourceManifestSha256\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\"").find(diagnostics.readText())
        return match?.groupValues?.get(1)?.lowercase()
            ?: throw IllegalArgumentException("Sparse diagnostics lack a valid sourceManifestSha256")
    }

    private fun writeStableConfig(root: File, config: Map<String, Any>) {
        val target = File(root, "object-config.json")
        val desired = reconstructionJson(config) + "\n"
        if (target.exists()) {
            require(target.isFile && target.readText() == desired) {
                "Object run configuration differs; use a new object-run directory: $root"
            }
        } else {
            target.writeText(desired)
        }
    }

    private fun invoke(worker: File, python: File, sparseRun: File, run: File, stageName: String,
                       timeoutSeconds: Long, runtimes: MutableMap<String, Double>) {
        val command = buildList {
            add(python.path); add(worker.path)
            add(sparseRun.path)
            add(run.path); add("--stage"); add(stageName); add("--config"); add(File(run, "object-config.json").path)
        }
        val log = File(run, "object-$stageName.log")
        val started = System.nanoTime()
        SparseCommand.runProcess(command, log, timeoutSeconds)
        runtimes[stageName] = (System.nanoTime() - started) / 1e9
    }

    private fun printDiagnostics(root: File, stdout: PrintStream) {
        val diagnostics = File(root, "diagnostics.json")
        require(diagnostics.isFile) { "Object worker succeeded without diagnostics: $diagnostics" }
        stdout.println(diagnostics.readText())
    }

    private fun requireOutside(source: File, output: File, message: String) {
        val sourcePath = source.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(outputPath != sourcePath && !outputPath.startsWith(sourcePath)) { "$message: $output" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun usage(command: String) = when (command) {
        "mesh-validate" -> "Usage: myndhamr mesh-validate <object-run> [--python path] [--repository path]"
        "object" -> "Usage: myndhamr object <scan> <object-run> [--sparse run] [object options]"
        else -> "Usage: myndhamr $command <sparse-run> <object-run> [object options]"
    }
}
