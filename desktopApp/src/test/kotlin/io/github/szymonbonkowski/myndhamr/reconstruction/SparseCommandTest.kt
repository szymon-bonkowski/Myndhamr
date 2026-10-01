package io.github.szymonbonkowski.myndhamr.reconstruction
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*
class SparseCommandTest {
    @Test fun argumentsFailBeforeWriting() {
        val out=ByteArrayOutputStream();val err=ByteArrayOutputStream()
        assertEquals(2,SparseCommand.run(listOf("reconstruct","missing","new","--no-such","1"),PrintStream(out),PrintStream(err)))
        assertContains(err.toString(),"Unknown sparse option")
    }
    @Test fun processFailureAndTimeoutKeepLogs() {
        val root=Files.createTempDirectory("sparse-process-test").toFile()
        try {
            val log=File(root,"exit.log")
            assertFailsWith<IllegalStateException>{SparseCommand.runProcess(listOf("sh","-c","echo known-failure; exit 7"),log,2)}
            assertContains(log.readText(),"known-failure")
            val before=System.nanoTime()
            assertFailsWith<IllegalStateException>{SparseCommand.runProcess(listOf("sh","-c","sleep 30"),File(root,"timeout.log"),1)}
            assertTrue((System.nanoTime()-before)/1e9<8,"Timed-out process must terminate")
        }finally{root.deleteRecursively()}
    }
    @Test fun interruptionTerminatesWorkerAndPreservesInterrupt() {
        val root=Files.createTempDirectory("sparse-interrupt-test").toFile()
        try {
            val log=File(root,"worker.log")
            var interrupted=false
            val thread=Thread {
                try { SparseCommand.runProcess(listOf("sh","-c","echo $$; exec sleep 30"),log,60) }
                catch(e:InterruptedException){interrupted=Thread.currentThread().isInterrupted}
            }
            thread.start()
            val limit=System.nanoTime()+2_000_000_000L
            while((!log.exists() || log.length()==0L) && System.nanoTime()<limit) Thread.sleep(10)
            val pid=log.readText().trim().toLong()
            thread.interrupt();thread.join(3000)
            assertFalse(thread.isAlive);assertTrue(interrupted)
            assertFalse(ProcessHandle.of(pid).map{it.isAlive}.orElse(false))
        }finally{root.deleteRecursively()}
    }
    @Test fun timeoutKillsDescendantIgnoringTerm() {
        val root=Files.createTempDirectory("sparse-descendant-test").toFile()
        try {
            val log=File(root,"worker.log")
            assertFailsWith<IllegalStateException> {
                SparseCommand.runProcess(listOf("sh","-c","sh -c 'trap \"\" TERM; echo $$; exec sleep 30' & wait"),log,1)
            }
            val pid=log.readText().trim().toLong()
            // A killed reparented process may briefly remain a zombie on Linux.
            val proc=File("/proc/$pid/stat")
            if(proc.exists()) assertContains(proc.readText(),") Z ")
        }finally{root.deleteRecursively()}
    }

    @Test fun adapterSuccessCannotHideMetricFailure() {
        val root=Files.createTempDirectory("sparse-failure-test").toFile()
        try {
            File(root,"adapter-diagnostics.json").writeText("{\"status\":\"succeeded\",\"registeredFraction\":1.0}")
            SparseCommand.writeFailureDiagnostics(root,mapOf("status" to "failed","stage" to "metric_alignment","error" to "timeout"))
            val diagnostic=File(root,"diagnostics.json").readText()
            assertTrue(diagnostic.startsWith("{\"status\":\"failed\""));assertContains(diagnostic,"metric_alignment")
            assertContains(diagnostic,"adapterDiagnostics")
        }finally{root.deleteRecursively()}
    }

}
