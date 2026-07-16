package io.github.ycfeng.ocdeck.data.project

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.recentProjectsDataStore by preferencesDataStore(name = "recent_projects")

class RecentProjectStore(
    context: Context,
    private val json: Json,
    private val pathNormalizer: PathNormalizer,
) {
    private val dataStore = context.applicationContext.recentProjectsDataStore

    fun observe(serverId: String): Flow<List<ProjectRef>> = dataStore.data
        .map { preferences ->
            preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
                .filter { it.serverId == serverId }
                .map { it.toProjectRef() }
        }
        .catch { emit(emptyList()) }

    suspend fun add(serverId: String, directory: String): ProjectRef {
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
                ?.toProjectRef()
                ?: project
            val otherServers = current.filterNot { it.serverId == serverId }
            val nextForServer = (listOf(project.toRecord(serverId)) + currentForServer)
                .dedupeByNormalizedDirectory()
                .take(MAX_RECENT_PROJECTS)
            preferences[recentProjectsKey] = json.encodeToString(otherServers + nextForServer)
        }
        return project
    }

    suspend fun upsert(serverId: String, project: ProjectRef): ProjectRef {
        val normalized = pathNormalizer.normalize(project.normalizedDirectory)
        val savedProject = project.copy(
            normalizedDirectory = normalized,
            displayName = project.displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
        )
        dataStore.edit { preferences ->
            val current = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            val currentForServer = current.filter { it.serverId == serverId }
            val otherServers = current.filterNot { it.serverId == serverId }
            val nextForServer = (listOf(savedProject.toRecord(serverId)) + currentForServer)
                .dedupeByNormalizedDirectory()
                .take(MAX_RECENT_PROJECTS)
            preferences[recentProjectsKey] = json.encodeToString(otherServers + nextForServer)
        }
        return savedProject
    }

    suspend fun remove(serverId: String, directory: String) {
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

    private fun List<RecentProjectRecord>.dedupeByNormalizedDirectory(): List<RecentProjectRecord> {
        val seen = linkedSetOf<String>()
        return filter { record -> seen.add(pathNormalizer.comparisonKey(record.normalizedDirectory)) }
    }

    private fun defaultProject(normalizedDirectory: String): ProjectRef = ProjectRef(
        normalizedDirectory = normalizedDirectory,
        projectId = null,
        displayName = normalizedDirectory.substringAfterLast('/').ifBlank { normalizedDirectory },
        vcs = null,
        icon = null,
    )

    private fun RecentProjectRecord.toProjectRef(): ProjectRef = ProjectRef(
        normalizedDirectory = normalizedDirectory,
        projectId = projectId,
        displayName = displayName,
        vcs = vcs,
        icon = null,
    )

    private fun ProjectRef.toRecord(serverId: String): RecentProjectRecord = RecentProjectRecord(
        serverId = serverId,
        normalizedDirectory = normalizedDirectory,
        projectId = projectId,
        displayName = displayName,
        vcs = vcs,
    )

    private companion object {
        const val MAX_RECENT_PROJECTS = 20
        val recentProjectsKey = stringPreferencesKey("recent_projects_json")
    }
}

@Serializable
private data class RecentProjectRecord(
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
