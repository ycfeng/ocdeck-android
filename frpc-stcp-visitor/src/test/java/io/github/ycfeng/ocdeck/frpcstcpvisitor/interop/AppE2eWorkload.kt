package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import kotlinx.serialization.Serializable

@Serializable
internal data class AppE2eWorkload(
    val profile: String,
    val iterations: Int,
    val concurrency: Int,
    val durationMillis: Long,
    val reconnectEveryIterations: Int,
    val sampleEveryIterations: Int,
)

@Serializable
internal data class AppE2eWorkloadResult(
    val profile: String,
    val completedIterations: Int,
    val reconnectCount: Int,
)

@Serializable
internal data class AppE2eResourceSnapshot(
    val elapsedRealtimeMillis: Long,
    val processCpuMillis: Long,
    val pssKiB: Long,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val openFdCount: Int,
    val threadCount: Int,
)

@Serializable
internal data class AppE2eResourceEvidence(
    val start: AppE2eResourceSnapshot,
    val end: AppE2eResourceSnapshot,
    val wallDurationMillis: Long,
    val cpuDurationMillis: Long,
    val samples: List<AppE2eResourceSnapshot>,
    val droppedSampleCount: Int,
)

internal fun parseAppE2eWorkload(
    suite: String,
    profileValue: String?,
    iterationsValue: String?,
    concurrencyValue: String?,
    durationMillisValue: String?,
    reconnectEveryIterationsValue: String?,
    sampleEveryIterationsValue: String?,
): AppE2eWorkload {
    val profile = profileValue?.takeIf(String::isNotBlank) ?: APP_E2E_PROFILE_FUNCTIONAL
    val defaults = when (profile) {
        APP_E2E_PROFILE_FUNCTIONAL -> AppE2eWorkload(profile, 0, 1, 0L, 0, 1)
        APP_E2E_PROFILE_BASELINE -> AppE2eWorkload(profile, 5, 1, 0L, 0, 1)
        APP_E2E_PROFILE_STRESS -> AppE2eWorkload(profile, 100, 8, 0L, 10, 5)
        APP_E2E_PROFILE_LEAK -> AppE2eWorkload(profile, 200, 1, 0L, 1, 5)
        APP_E2E_PROFILE_SOAK -> AppE2eWorkload(profile, 100_000, 4, 21_600_000L, 25, 25)
        else -> throw InteropFailure("configured App E2E resource profile was invalid")
    }
    fun integer(value: String?, default: Int): Int {
        if (value.isNullOrBlank()) return default
        return value.toIntOrNull() ?: throw InteropFailure("configured App E2E workload was invalid")
    }
    fun long(value: String?, default: Long): Long {
        if (value.isNullOrBlank()) return default
        return value.toLongOrNull() ?: throw InteropFailure("configured App E2E workload was invalid")
    }
    val workload = defaults.copy(
        iterations = integer(iterationsValue, defaults.iterations),
        concurrency = integer(concurrencyValue, defaults.concurrency),
        durationMillis = long(durationMillisValue, defaults.durationMillis),
        reconnectEveryIterations = integer(
            reconnectEveryIterationsValue,
            defaults.reconnectEveryIterations,
        ),
        sampleEveryIterations = integer(sampleEveryIterationsValue, defaults.sampleEveryIterations),
    )
    if (workload.iterations !in 0..MAXIMUM_APP_E2E_WORKLOAD_ITERATIONS ||
        workload.concurrency !in 1..MAXIMUM_APP_E2E_WORKLOAD_CONCURRENCY ||
        workload.reconnectEveryIterations !in 0..maxOf(1, workload.iterations) ||
        workload.sampleEveryIterations !in 1..maxOf(1, workload.iterations) ||
        (profile == APP_E2E_PROFILE_FUNCTIONAL && workload != defaults) ||
        (profile != APP_E2E_PROFILE_FUNCTIONAL && (suite != APP_E2E_WORKLOAD_SUITE_CORE || workload.iterations == 0)) ||
        (profile == APP_E2E_PROFILE_SOAK &&
            workload.durationMillis !in MINIMUM_APP_E2E_SOAK_MILLIS..MAXIMUM_APP_E2E_SOAK_MILLIS) ||
        (profile != APP_E2E_PROFILE_SOAK && workload.durationMillis != 0L) ||
        (profile == APP_E2E_PROFILE_LEAK && workload.reconnectEveryIterations == 0)
    ) {
        throw InteropFailure("configured App E2E workload was invalid")
    }
    return workload
}

internal fun isValidAppE2eResourceEvidence(
    evidence: AppE2eResourceEvidence,
    workload: AppE2eWorkload,
    workloadResult: AppE2eWorkloadResult?,
): Boolean {
    fun valid(snapshot: AppE2eResourceSnapshot): Boolean =
        snapshot.elapsedRealtimeMillis >= 0L && snapshot.processCpuMillis >= 0L && snapshot.pssKiB >= 0L &&
            snapshot.javaHeapBytes >= 0L && snapshot.nativeHeapBytes >= 0L &&
            snapshot.openFdCount in 1..MAXIMUM_APP_E2E_RESOURCE_COUNT &&
            snapshot.threadCount in 1..MAXIMUM_APP_E2E_RESOURCE_COUNT
    val scheduledSampleCount = when {
        workload.profile == APP_E2E_PROFILE_FUNCTIONAL && workloadResult == null -> 0
        workload.profile == APP_E2E_PROFILE_FUNCTIONAL || workloadResult == null -> return false
        workloadResult.profile != workload.profile ||
            workloadResult.completedIterations !in 1..workload.iterations -> return false
        else -> workloadResult.completedIterations / workload.sampleEveryIterations
    }
    if (workload.profile != APP_E2E_PROFILE_FUNCTIONAL && scheduledSampleCount == 0) return false
    val expectedStoredSampleCount = minOf(scheduledSampleCount, MAXIMUM_APP_E2E_RESOURCE_SAMPLES)
    val expectedDroppedSampleCount = maxOf(scheduledSampleCount - MAXIMUM_APP_E2E_RESOURCE_SAMPLES, 0)
    val samplesValid = evidence.samples.size == expectedStoredSampleCount &&
        evidence.droppedSampleCount == expectedDroppedSampleCount && evidence.samples.all(::valid) &&
        evidence.samples.zipWithNext().all { (first, second) ->
            second.elapsedRealtimeMillis >= first.elapsedRealtimeMillis &&
                second.processCpuMillis >= first.processCpuMillis
        } && evidence.samples.all {
            it.elapsedRealtimeMillis in evidence.start.elapsedRealtimeMillis..evidence.end.elapsedRealtimeMillis &&
                it.processCpuMillis in evidence.start.processCpuMillis..evidence.end.processCpuMillis
        }
    return valid(evidence.start) && valid(evidence.end) && samplesValid &&
        evidence.wallDurationMillis == evidence.end.elapsedRealtimeMillis - evidence.start.elapsedRealtimeMillis &&
        evidence.cpuDurationMillis == evidence.end.processCpuMillis - evidence.start.processCpuMillis &&
        evidence.wallDurationMillis >= 0L && evidence.cpuDurationMillis >= 0L &&
        evidence.cpuDurationMillis <= evidence.wallDurationMillis * 64L
}

internal fun appE2eInstrumentationTimeoutMillis(workload: AppE2eWorkload): Long {
    val workloadBudget = if (workload.profile == APP_E2E_PROFILE_SOAK) {
        workload.durationMillis
    } else {
        workload.iterations.toLong() * APP_E2E_ITERATION_TIMEOUT_BUDGET_MILLIS
    }
    return (APP_E2E_INSTRUMENTATION_BASE_TIMEOUT_MILLIS + workloadBudget)
        .coerceAtMost(MAXIMUM_APP_E2E_INSTRUMENTATION_TIMEOUT_MILLIS)
}

internal const val APP_E2E_PROFILE_FUNCTIONAL = "functional"
internal const val APP_E2E_PROFILE_BASELINE = "baseline"
internal const val APP_E2E_PROFILE_STRESS = "stress"
internal const val APP_E2E_PROFILE_LEAK = "leak"
internal const val APP_E2E_PROFILE_SOAK = "soak"
internal const val MAXIMUM_APP_E2E_RESOURCE_COUNT = 16_384
internal const val MAXIMUM_APP_E2E_RESOURCE_SAMPLES = 254
internal const val MAXIMUM_APP_E2E_WORKLOAD_ITERATIONS = 100_000
internal const val MAXIMUM_APP_E2E_WORKLOAD_CONCURRENCY = 32
internal const val MINIMUM_APP_E2E_SOAK_MILLIS = 60_000L
internal const val MAXIMUM_APP_E2E_SOAK_MILLIS = 86_400_000L
private const val APP_E2E_WORKLOAD_SUITE_CORE = "core"
private const val APP_E2E_ITERATION_TIMEOUT_BUDGET_MILLIS = 30_000L
private const val APP_E2E_INSTRUMENTATION_BASE_TIMEOUT_MILLIS = 660_000L
private const val MAXIMUM_APP_E2E_INSTRUMENTATION_TIMEOUT_MILLIS =
    MAXIMUM_APP_E2E_SOAK_MILLIS + APP_E2E_INSTRUMENTATION_BASE_TIMEOUT_MILLIS
