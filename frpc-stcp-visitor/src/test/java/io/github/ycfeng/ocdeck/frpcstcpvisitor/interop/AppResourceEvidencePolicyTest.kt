package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResourceEvidencePolicyTest {
    @Test
    fun matchingEvidenceWithinPolicyPasses() {
        val evaluation = evaluateAppResourcePolicy(
            input("gomobile", cpu = 100_000, pss = 80_000, threads = 100),
            input("kotlin", cpu = 140_000, pss = 60_000, threads = 104),
        )

        assertTrue(evaluation.failures.isEmpty())
        assertEquals(1.4, evaluation.cpuRegressionRatio!!, 0.001)
    }

    @Test
    fun cpuRegressionFailsClosed() {
        val evaluation = evaluateAppResourcePolicy(
            input("gomobile", cpu = 100_000, pss = 80_000, threads = 100),
            input("kotlin", cpu = 230_000, pss = 60_000, threads = 104),
        )

        assertTrue(evaluation.failures.any { it.contains("process CPU") })
    }

    @Test
    fun leakWindowThreadGrowthFailsClosed() {
        val evaluation = evaluateAppResourcePolicy(
            input("gomobile", cpu = 100_000, pss = 80_000, threads = 100),
            input("kotlin", cpu = 140_000, pss = 60_000, threads = 100, threadGrowth = 12),
        )

        assertTrue(evaluation.failures.any { it.contains("thread count increased") })
    }

    @Test
    fun candidateMismatchFailsClosed() {
        val goMobile = input("gomobile", cpu = 100_000, pss = 80_000, threads = 100)
        val kotlin = input("kotlin", cpu = 140_000, pss = 60_000, threads = 104).copy(
            repository = buildJsonObject { put("candidate", "different") },
        )

        val evaluation = evaluateAppResourcePolicy(goMobile, kotlin)

        assertTrue(evaluation.failures.any { it.contains("same candidate") })
    }

    @Test
    fun postCleanupThreadResidueFailsClosed() {
        val goMobile = input("gomobile", cpu = 100_000, pss = 80_000, threads = 100)
        val kotlin = input("kotlin", cpu = 140_000, pss = 60_000, threads = 100).let { input ->
            input.copy(quiescence = input.quiescence.copy(threadCount = input.start.threadCount + 9))
        }

        val evaluation = evaluateAppResourcePolicy(goMobile, kotlin)

        assertTrue(evaluation.failures.any { it.contains("post-cleanup thread") })
    }

    @Test
    fun legacySummarySchemaFailsClosedBeforePayloadParsing() {
        val file = Files.createTempFile("ocdeck-resource-schema", ".json")
        try {
            Files.write(file, "{\"schemaVersion\":8}".toByteArray())

            assertThrows(IllegalArgumentException::class.java) {
                loadAppResourcePolicyInput(file.toFile(), "gomobile")
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun validSchemaNineEvidenceLoads() {
        withEvidenceFile(evidenceJson()) { file ->
            val input = loadAppResourcePolicyInput(file, "gomobile")

            assertEquals(10, input.workloadResult.completedIterations)
            assertEquals((1..5).toList(), input.samples.map { it.sampleOrdinal })
        }
    }

    @Test
    fun incompleteWorkloadReconnectMismatchAndFalseOrdinalsFailClosed() {
        listOf(
            evidenceJson(completedIterations = 9, reconnectCount = 4),
            evidenceJson(reconnectCount = 4),
            evidenceJson(sampleOrdinals = listOf(1, 2, 3, 4, 4)),
        ).forEach { evidence ->
            withEvidenceFile(evidence) { file ->
                assertThrows(IllegalArgumentException::class.java) {
                    loadAppResourcePolicyInput(file, "gomobile")
                }
            }
        }
    }

    @Test
    fun comparisonThresholdUsesEitherBackendDuration() {
        val evaluation = evaluateAppResourcePolicy(
            input("gomobile", cpu = 40_000, pss = 80_000, threads = 100, wall = 59_000),
            input("kotlin", cpu = 40_000, pss = 60_000, threads = 104, wall = 118_000),
        )

        assertTrue(evaluation.failures.any { it.contains("wall duration") })
    }

    private fun input(
        backend: String,
        cpu: Long,
        pss: Long,
        threads: Long,
        threadGrowth: Long = 0,
        wall: Long = 120_000,
    ): AppResourcePolicyInput {
        val samples = (0 until 16).map { index ->
            AppResourcePolicySnapshot(
                elapsedRealtimeMillis = index * 1_000L,
                processCpuMillis = index * 100L,
                pssKiB = pss + index * 32,
                javaHeapBytes = 8L * 1024 * 1024,
                nativeHeapBytes = 12L * 1024 * 1024,
                openFdCount = 100,
                threadCount = threads + if (index >= 12) threadGrowth else 0,
                sampleOrdinal = index + 1,
            )
        }
        val workload = AppE2eWorkload("leak", 16, 1, 0, 4, 1)
        return AppResourcePolicyInput(
            backend = backend,
            profile = "leak",
            repository = buildJsonObject { put("candidate", "same") },
            device = buildJsonObject { put("serial", "same") },
            workload = workload,
            workloadResult = AppE2eWorkloadResult("leak", 16, 4),
            wallDurationMillis = wall,
            cpuDurationMillis = cpu,
            start = samples.first(),
            end = samples.last(),
            samples = samples,
            quiescence = samples.first().copy(
                elapsedRealtimeMillis = samples.last().elapsedRealtimeMillis + APP_E2E_RESOURCE_QUIESCENCE_MILLIS,
                processCpuMillis = samples.last().processCpuMillis,
            ),
        )
    }

    private fun evidenceJson(
        completedIterations: Int = 10,
        reconnectCount: Int = 5,
        sampleOrdinals: List<Int> = (1..5).toList(),
    ): String = buildJsonObject {
            put("schemaVersion", 9)
            put("backend", "gomobile")
            put("suite", "core")
            put("status", "passed")
            put("cleanupVerified", true)
            put("authorizesKotlinDefault", false)
            putJsonObject("repository") {
                put("candidate", "same")
                put("worktreeClean", true)
            }
            putJsonObject("device") { put("serial", "same") }
            putJsonObject("targetApk") { put("byteIdentical", true) }
            putJsonObject("testApk") { put("byteIdentical", true) }
            putJsonObject("workload") {
                put("profile", "stress")
                put("iterations", 10)
                put("concurrency", 1)
                put("durationMillis", 0)
                put("reconnectEveryIterations", 2)
                put("sampleEveryIterations", 2)
            }
            putJsonArray("workloads") {
                add(buildJsonObject {
                    put("profile", "stress")
                    put("completedIterations", completedIterations)
                    put("reconnectCount", reconnectCount)
                })
            }
            putJsonArray("resources") {
                add(buildJsonObject {
                    putJsonObject("start") { addSnapshot(0, 0, null) }
                    putJsonObject("end") { addSnapshot(120_000, 10_000, null) }
                    put("wallDurationMillis", 120_000)
                    put("cpuDurationMillis", 10_000)
                    putJsonArray("samples") {
                        sampleOrdinals.forEach { ordinal ->
                            add(buildJsonObject { addSnapshot(ordinal * 1_000L, ordinal * 100L, ordinal) })
                        }
                    }
                    put("droppedSampleCount", 0)
                    putJsonObject("sampleRetention") {
                        put("strategy", APP_E2E_RESOURCE_SAMPLE_RETENTION_STRATEGY)
                        put("observedSampleCount", 5)
                        put("retainedHeadSampleCount", 5)
                        put("retainedTailSampleCount", 0)
                    }
                    putJsonObject("quiescence") {
                        put("requestedDelayMillis", APP_E2E_RESOURCE_QUIESCENCE_MILLIS)
                        putJsonObject("snapshot") { addSnapshot(210_000, 10_000, null) }
                    }
                })
            }
        }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.addSnapshot(
        elapsed: Long,
        cpu: Long,
        ordinal: Int?,
    ) {
        put("elapsedRealtimeMillis", elapsed)
        put("processCpuMillis", cpu)
        put("pssKiB", 64_000)
        put("javaHeapBytes", 8L * 1024 * 1024)
        put("nativeHeapBytes", 12L * 1024 * 1024)
        put("openFdCount", 100)
        put("threadCount", 100)
        if (ordinal != null) put("sampleOrdinal", ordinal)
    }

    private fun withEvidenceFile(evidence: String, block: (java.io.File) -> Unit) {
        val file = Files.createTempFile("ocdeck-resource-policy", ".json")
        try {
            Files.write(file, evidence.toByteArray())
            block(file.toFile())
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
