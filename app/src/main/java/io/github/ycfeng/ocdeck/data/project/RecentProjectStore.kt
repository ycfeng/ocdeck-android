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
            val currentForServer = orderedDistinctRecentProjectRecords(current, serverId, pathNormalizer)
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

    override suspend fun reorder(
        serverId: String,
        projects: List<ProjectRef>,
        projectToEnsure: ProjectRef?,
    ) {
        dataStore.edit { preferences ->
            val current = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            val next = reorderRecentProjectRecords(
                records = current,
                serverId = serverId,
                projects = projects,
                projectToEnsure = projectToEnsure,
                pathNormalizer = pathNormalizer,
            )
            preferences[recentProjectsKey] = json.encodeToString(next)
        }
    }

    override suspend fun remove(serverId: String, directory: String) {
        val targetKey = pathNormalizer.comparisonKey(pathNormalizer.normalize(directory))
        dataStore.edit { preferences ->
            val current = preferences[recentProjectsKey]
                ?.let { json.decodeFromString<List<RecentProjectRecord>>(it) }
                .orEmpty()
            val nextForServer = orderedDistinctRecentProjectRecords(current, serverId, pathNormalizer)
                .filterNot { pathNormalizer.comparisonKey(it.normalizedDirectory) == targetKey }
            val next = replaceRecentProjectRecordsForServer(current, serverId, nextForServer)
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
    val sortOrder: Int? = null,
) {
    override fun toString(): String =
        "RecentProjectRecord(serverId=<redacted>, normalizedDirectory=<redacted>, " +
            "projectId=${if (projectId == null) "null" else "<redacted>"}, " +
            "displayName=<redacted>, vcsPresent=${vcs != null}, sortOrder=$sortOrder)"
}

internal fun recentProjectsForServer(
    records: List<RecentProjectRecord>,
    serverId: String,
    pathNormalizer: PathNormalizer,
): List<ProjectRef> {
    return orderedDistinctRecentProjectRecords(records, serverId, pathNormalizer).asSequence()
        .map { it.toProjectRef(pathNormalizer) }
        .take(MAX_RECENT_PROJECTS)
        .toList()
}

internal fun upsertRecentProjectRecord(
    records: List<RecentProjectRecord>,
    serverId: String,
    project: ProjectRef,
    pathNormalizer: PathNormalizer,
): List<RecentProjectRecord> {
    val savedProject = project.normalized(pathNormalizer)
    val targetKey = pathNormalizer.comparisonKey(savedProject.normalizedDirectory)
    val nextForServer = orderedDistinctRecentProjectRecords(records, serverId, pathNormalizer).toMutableList()
    val existingIndex = nextForServer.indexOfFirst {
        pathNormalizer.comparisonKey(it.normalizedDirectory) == targetKey
    }
    val savedRecord = savedProject.toRecentProjectRecord(serverId)
    if (existingIndex >= 0) {
        nextForServer[existingIndex] = savedRecord
    } else {
        nextForServer.add(0, savedRecord)
    }
    return replaceRecentProjectRecordsForServer(records, serverId, nextForServer)
}

internal fun reorderRecentProjectRecords(
    records: List<RecentProjectRecord>,
    serverId: String,
    projects: List<ProjectRef>,
    projectToEnsure: ProjectRef? = null,
    pathNormalizer: PathNormalizer,
): List<RecentProjectRecord> {
    val currentForServer = orderedDistinctRecentProjectRecords(records, serverId, pathNormalizer)
        .take(MAX_RECENT_PROJECTS)
    val currentByKey = currentForServer.associateBy {
        pathNormalizer.comparisonKey(it.normalizedDirectory)
    }
    val ensuredProject = projectToEnsure?.normalized(pathNormalizer)
    val ensuredKey = ensuredProject?.let { pathNormalizer.comparisonKey(it.normalizedDirectory) }
    val submittedKeys = linkedSetOf<String>()
    val reordered = projects.map { it.normalized(pathNormalizer) }
        .filter { submittedKeys.add(pathNormalizer.comparisonKey(it.normalizedDirectory)) }
        .mapNotNull { project ->
            val key = pathNormalizer.comparisonKey(project.normalizedDirectory)
            currentByKey[key] ?: ensuredProject
                ?.takeIf { key == ensuredKey }
                ?.toRecentProjectRecord(serverId)
        }
        .toMutableList()
    val submittedCurrentKeys = submittedKeys.filterTo(mutableSetOf()) { it in currentByKey }
    currentForServer.forEachIndexed { currentIndex, record ->
        val key = pathNormalizer.comparisonKey(record.normalizedDirectory)
        if (key in submittedKeys) return@forEachIndexed
        val nextSubmittedKey = currentForServer.asSequence()
            .drop(currentIndex + 1)
            .map { pathNormalizer.comparisonKey(it.normalizedDirectory) }
            .firstOrNull { it in submittedCurrentKeys }
        val insertionIndex = nextSubmittedKey
            ?.let { nextKey ->
                reordered.indexOfFirst {
                    pathNormalizer.comparisonKey(it.normalizedDirectory) == nextKey
                }.takeIf { it >= 0 }
            }
            ?: reordered.size
        reordered.add(insertionIndex, record)
    }
    if (ensuredKey != null && ensuredKey !in currentByKey) {
        while (reordered.size > MAX_RECENT_PROJECTS) {
            val removalIndex = reordered.indexOfLast {
                pathNormalizer.comparisonKey(it.normalizedDirectory) != ensuredKey
            }
            if (removalIndex < 0) break
            reordered.removeAt(removalIndex)
        }
    }
    return replaceRecentProjectRecordsForServer(records, serverId, reordered)
}

private fun orderedDistinctRecentProjectRecords(
    records: List<RecentProjectRecord>,
    serverId: String,
    pathNormalizer: PathNormalizer,
): List<RecentProjectRecord> {
    val seen = linkedSetOf<String>()
    return records.withIndex().asSequence()
        .filter { it.value.serverId == serverId }
        .sortedWith(
            compareBy<IndexedValue<RecentProjectRecord>>(
                { if (it.value.sortOrder == null) 1 else 0 },
                { it.value.sortOrder ?: 0 },
                { it.index },
            ),
        )
        .map { it.value.normalized(pathNormalizer) }
        .filter { seen.add(pathNormalizer.comparisonKey(it.normalizedDirectory)) }
        .toList()
}

private fun replaceRecentProjectRecordsForServer(
    records: List<RecentProjectRecord>,
    serverId: String,
    nextForServer: List<RecentProjectRecord>,
): List<RecentProjectRecord> = records.filterNot { it.serverId == serverId } +
    nextForServer.take(MAX_RECENT_PROJECTS).mapIndexed { index, record ->
        record.copy(serverId = serverId, sortOrder = index)
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

private fun ProjectRef.normalized(pathNormalizer: PathNormalizer): ProjectRef {
    val normalized = pathNormalizer.normalize(normalizedDirectory)
    return copy(
        normalizedDirectory = normalized,
        displayName = displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
    )
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

private fun RecentProjectRecord.normalized(pathNormalizer: PathNormalizer): RecentProjectRecord {
    val normalized = pathNormalizer.normalize(normalizedDirectory)
    return copy(
        normalizedDirectory = normalized,
        displayName = displayName.ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
    )
}

private const val MAX_RECENT_PROJECTS = 20
