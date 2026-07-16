package io.github.ycfeng.ocdeck.data.project

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.recentProjectsDataStore by preferencesDataStore(name = "recent_projects")

class RecentProjectStore(
    context: Context,
    private val json: Json,
    private val pathNormalizer: PathNormalizer,
    private val failureReporter: RecentProjectFailureReporter,
) : RecentProjectRepository {
    private val dataStore = context.applicationContext.recentProjectsDataStore

    override fun observe(serverId: String): Flow<List<ProjectRef>> = dataStore.data
        .map { preferences ->
            val records = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            recentProjectsForServer(records, serverId, pathNormalizer)
        }
        .catch { throwable ->
            handleRecentProjectReadFailure(throwable, failureReporter)
            emit(emptyList())
        }

    override suspend fun add(serverId: String, directory: String): ProjectRef {
        val normalized = pathNormalizer.normalize(directory)
        val targetKey = pathNormalizer.comparisonKey(normalized)
        var project = defaultProject(normalized)
        dataStore.edit { preferences ->
            val current = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            val currentForServer = current.filter { it.serverId == serverId }
            project = currentForServer
                .firstOrNull { pathNormalizer.comparisonKey(it.normalizedDirectory) == targetKey }
                ?.toProjectRef(pathNormalizer)
                ?: project
            val next = upsertRecentProjectRecord(current, serverId, project, pathNormalizer)
            preferences[recentProjectsKey] = json.encodeToString(next)
        }
        return project
    }

    override suspend fun upsert(serverId: String, project: ProjectRef): ProjectRef {
        val normalized = pathNormalizer.normalize(project.normalizedDirectory)
        val savedProject = project.copy(
            normalizedDirectory = normalized,
            displayName = project.displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
        )
        dataStore.edit { preferences ->
            val current = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            val next = upsertRecentProjectRecord(current, serverId, savedProject, pathNormalizer)
            preferences[recentProjectsKey] = json.encodeToString(next)
        }
        return savedProject
    }

    override suspend fun remove(serverId: String, directory: String) {
        val targetKey = pathNormalizer.comparisonKey(pathNormalizer.normalize(directory))
        dataStore.edit { preferences ->
            val current = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            val next = current.filterNot { record ->
                record.serverId == serverId &&
                    pathNormalizer.comparisonKey(record.normalizedDirectory) == targetKey
            }
            preferences[recentProjectsKey] = json.encodeToString(next)
        }
    }

    private fun defaultProject(normalizedDirectory: String): ProjectRef = ProjectRef(
        normalizedDirectory = normalizedDirectory,
        projectId = null,
        displayName = normalizedDirectory.substringAfterLast('/').ifBlank { normalizedDirectory },
        vcs = null,
        icon = null,
    )

    private companion object {
        val recentProjectsKey = stringPreferencesKey("recent_projects_json")
    }
}

@Serializable
internal data class RecentProjectRecord(
    val serverId: String,
    val normalizedDirectory: String,
    val projectId: String? = null,
    val displayName: String,
    val vcs: String? = null,
) {
    override fun toString(): String =
        "RecentProjectRecord(serverId=<redacted>, normalizedDirectory=<redacted>, " +
            "projectId=${if (projectId == null) "null" else "<redacted>"}, " +
            "displayName=<redacted>, vcsPresent=${vcs != null})"
}

internal fun recentProjectsForServer(
    records: List<RecentProjectRecord>,
    serverId: String,
    pathNormalizer: PathNormalizer,
): List<ProjectRef> {
    val seen = linkedSetOf<String>()
    return records.asSequence()
        .filter { it.serverId == serverId }
        .map { record ->
            val normalized = pathNormalizer.normalize(record.normalizedDirectory)
            ProjectRef(
                normalizedDirectory = normalized,
                projectId = record.projectId,
                displayName = record.displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
                vcs = record.vcs,
                icon = null,
            )
        }
        .filter { project -> seen.add(pathNormalizer.comparisonKey(project.normalizedDirectory)) }
        .take(MAX_RECENT_PROJECTS)
        .toList()
}

internal fun upsertRecentProjectRecord(
    records: List<RecentProjectRecord>,
    serverId: String,
    project: ProjectRef,
    pathNormalizer: PathNormalizer,
): List<RecentProjectRecord> {
    val normalized = pathNormalizer.normalize(project.normalizedDirectory)
    val savedProject = project.copy(
        normalizedDirectory = normalized,
        displayName = project.displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
    )
    val seen = linkedSetOf<String>()
    val currentForServer = records.filter { it.serverId == serverId }
    val nextForServer = (sequenceOf(savedProject.toRecentProjectRecord(serverId)) + currentForServer.asSequence())
        .filter { record -> seen.add(pathNormalizer.comparisonKey(record.normalizedDirectory)) }
        .take(MAX_RECENT_PROJECTS)
        .toList()
    return records.filterNot { it.serverId == serverId } + nextForServer
}

internal fun handleRecentProjectReadFailure(
    throwable: Throwable,
    failureReporter: RecentProjectFailureReporter,
) {
    when (throwable) {
        is CancellationException -> throw throwable
        is Error -> throw throwable
        is SerializationException -> failureReporter.reportSafely(RecentProjectFailure.ReadSerialization)
        is CorruptionException -> failureReporter.reportSafely(RecentProjectFailure.ReadCorruption)
        is IOException -> failureReporter.reportSafely(RecentProjectFailure.ReadIo)
        else -> failureReporter.reportSafely(RecentProjectFailure.ReadUnexpected)
    }
}

private fun ProjectRef.toRecentProjectRecord(serverId: String): RecentProjectRecord = RecentProjectRecord(
    serverId = serverId,
    normalizedDirectory = normalizedDirectory,
    projectId = projectId,
    displayName = displayName,
    vcs = vcs,
)

private fun RecentProjectRecord.toProjectRef(pathNormalizer: PathNormalizer): ProjectRef {
    val normalized = pathNormalizer.normalize(normalizedDirectory)
    return ProjectRef(
        normalizedDirectory = normalized,
        projectId = projectId,
        displayName = displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
        vcs = vcs,
        icon = null,
    )
}

private const val MAX_RECENT_PROJECTS = 20
