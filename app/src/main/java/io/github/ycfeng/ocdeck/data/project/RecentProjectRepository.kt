package io.github.ycfeng.ocdeck.data.project

import android.util.Log
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

interface RecentProjectRepository {
    fun observe(serverId: String): Flow<List<ProjectRef>>

    suspend fun add(serverId: String, directory: String): ProjectRef

    suspend fun upsert(serverId: String, project: ProjectRef): ProjectRef

    suspend fun reorder(
        serverId: String,
        projects: List<ProjectRef>,
        projectToEnsure: ProjectRef? = null,
    )

    suspend fun remove(serverId: String, directory: String)
}

enum class RecentProjectFailure {
    ReadIo,
    ReadSerialization,
    ReadCorruption,
    ReadUnexpected,
    WriteIo,
    WriteSerialization,
    WriteCorruption,
    WriteUnexpected,
    QueueFull,
}

fun interface RecentProjectFailureReporter {
    fun report(failure: RecentProjectFailure)
}

class AndroidRecentProjectFailureReporter : RecentProjectFailureReporter {
    override fun report(failure: RecentProjectFailure) {
        Log.w(TAG, failure.name)
    }

    private companion object {
        const val TAG = "RecentProjects"
    }
}

internal fun RecentProjectFailureReporter.reportSafely(failure: RecentProjectFailure) {
    try {
        report(failure)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Error) {
        throw error
    } catch (_: Exception) {
        // Reporting must not turn a local persistence failure into an app failure.
    }
}
