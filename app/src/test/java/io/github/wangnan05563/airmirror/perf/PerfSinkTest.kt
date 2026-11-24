package io.github.wangnan05563.airmirror.perf

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerfSinkTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "perf-sink-test-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes jsonl lines and rotates by size`() {
        var now = 1_700_000_000_000L
        val sink = PerfSink(dir, clock = { now }, maxBytes = 60, maxFiles = 8)
        repeat(8) { index ->
            sink.write(PerfTrace.line(now, "s", "e", mapOf("i" to index)))
            now += 1_000L
        }
        val files = sink.list()
        assertTrue(files.size >= 2)
        assertTrue(files.all { it.isFile && it.name.startsWith("perf-") })
        val lines = files.sumOf { it.readLines().size }
        assertEquals(8, lines)
    }

    @Test
    fun `purges expired files and keeps the newest`() {
        val old = File(dir, "perf-20200101.jsonl").apply { writeText("{}\n") }
        val mid = File(dir, "perf-20200102.jsonl").apply { writeText("{}\n") }
        val fresh = File(dir, "perf-20200103.jsonl").apply { writeText("{}\n") }
        val now = 1_700_000_000_000L
        old.setLastModified(now - PerfTrace.RETENTION_MS - 1)
        mid.setLastModified(now - 1_000L)
        fresh.setLastModified(now)
        val sink = PerfSink(dir, clock = { now }, maxBytes = 10_000, maxFiles = 2)
        assertEquals(1, sink.purge(now))
        assertEquals(setOf(mid.name, fresh.name), sink.list().map { it.name }.toSet())
    }

    @Test
    fun `does not touch unrelated files`() {
        val keep = File(dir, "diagnostics").apply { mkdirs() }
        File(keep, "crash-1.txt").writeText("x")
        val sink = PerfSink(dir, maxFiles = 1)
        sink.write(PerfTrace.line(1L, "s", "e"))
        assertTrue(File(keep, "crash-1.txt").isFile)
    }
}
