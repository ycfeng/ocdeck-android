package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.network.OpenCodeApi
import io.github.ycfeng.ocdeck.core.network.PathInfoDto
import io.github.ycfeng.ocdeck.core.network.ProjectDto
import io.github.ycfeng.ocdeck.core.network.SessionDto
import io.github.ycfeng.ocdeck.core.network.SessionStatusDto
import io.github.ycfeng.ocdeck.core.network.SessionTimeDto
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.SessionListWindowLoadRequest
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.data.server.OpenCodeServerRepository
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerConnection
import io.github.ycfeng.ocdeck.data.server.ServerHiddenModelPreference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class OpenCodeRepositorySessionWindowTest {
    @Test
    fun projectSnapshotRequestsRootsWithExplicitWindowAndStableSort() = runTest {
        val calls = mutableListOf<List<Any?>>()
        val api = fakeApi(
            sessions = {
                listOf(
                    sessionDto("a", updatedAt = 1L),
                    sessionDto("b", updatedAt = 5L),
                    sessionDto("c", updatedAt = 5L),
                )
            },
            onGetSessions = { calls += it },
        )
        val snapshot = repository(api).loadProject(
            serverId = serverId,
            directory = "E:\\work\\app\\",
            sessionWindow = SessionListWindowState(requestedRawLimit = 70, requestGeneration = 4L),
            workspace = workspace,
        ).getOrThrow()

        assertEquals(listOf("E:/work/app", workspace, true, 70), calls.single().take(4))
        assertEquals(listOf("c", "b", "a"), snapshot.sessions.map { it.id })
        assertEquals(3, snapshot.sessionWindowRawResultCount)
        assertEquals(70, snapshot.sessionWindowRequestedRawLimit)
        assertEquals(4L, snapshot.sessionWindowRequestGeneration)
    }

    @Test
    fun dedicatedWindowLoadUsesExplicitLimitAndWorkspace() = runTest {
        val calls = mutableListOf<List<Any?>>()
        val api = fakeApi(
            sessions = { listOf(sessionDto("session")) },
            onGetSessions = { calls += it },
        )
        val loaded = repository(api).loadSessionListWindow(
            serverId = serverId,
            directory = directory,
            workspace = workspace,
            request = SessionListWindowLoadRequest(
                requestedRawLimit = 90,
                requestGeneration = 7L,
                expectedProjectRevision = 3L,
            ),
        ).getOrThrow()

        assertEquals(listOf(directory, workspace, true, 90), calls.single().take(4))
        assertEquals(90, loaded.requestedRawLimit)
        assertEquals(1, loaded.rawResultCount)
        assertEquals(7L, loaded.requestGeneration)
    }

    @Test
    fun sessionMetadataParentChainStopsAtCycle() = runTest {
        val requestedIds = mutableListOf<String>()
        val api = fakeApi(
            getSession = { id, _, requestedWorkspace ->
                requestedIds += id
                assertEquals(workspace, requestedWorkspace)
                when (id) {
                    "a" -> sessionDto("a", parentId = "b")
                    "b" -> sessionDto("b", parentId = "a")
                    else -> error("unexpected session")
                }
            },
        )

        val sessions = repository(api).loadSessionMetadataChain(
            serverId = serverId,
            directory = "E:\\work\\app\\",
            sessionId = "a",
            workspace = workspace,
        ).getOrThrow()

        assertEquals(listOf("a", "b"), sessions.map { it.id })
        assertEquals(listOf("a", "b"), requestedIds)
        assertTrue(sessions.all { it.normalizedDirectory == directory })
    }

    @Test
    fun sessionMetadataParentChainHasDepthLimit() = runTest {
        val requestedIds = mutableListOf<String>()
        val api = fakeApi(
            getSession = { id, _, _ ->
                requestedIds += id
                val index = id.removePrefix("session-").toInt()
                sessionDto(id, parentId = "session-${index + 1}")
            },
        )

        val sessions = repository(api).loadSessionMetadataChain(
            serverId = serverId,
            directory = directory,
            sessionId = "session-0",
        ).getOrThrow()

        assertEquals(MaxSessionMetadataDepth, sessions.size)
        assertEquals("session-${MaxSessionMetadataDepth - 1}", sessions.last().id)
        assertEquals(MaxSessionMetadataDepth, requestedIds.size)
    }

    private fun repository(api: OpenCodeApi): OpenCodeRepository = OpenCodeRepository(
        serverRepository = FakeOpenCodeServerRepository(api),
        pathNormalizer = PathNormalizer(),
        projectFilePathNormalizer = ProjectFilePathNormalizer(),
        json = Json { ignoreUnknownKeys = true },
        redactor = Redactor(),
    )

    private fun fakeApi(
        sessions: () -> List<SessionDto> = { emptyList() },
        onGetSessions: (List<Any?>) -> Unit = {},
        getSession: (String, String, String?) -> SessionDto = { id, requestedDirectory, _ ->
            sessionDto(id, directory = requestedDirectory)
        },
    ): OpenCodeApi {
        val emptyJson = JsonObject(emptyMap())
        val handler = InvocationHandler { proxy, method, args ->
            val arguments = args?.toList().orEmpty()
            when (method.name) {
                "toString" -> "FakeOpenCodeApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "getPath" -> PathInfoDto(directory = directory)
                "getCurrentProject" -> ProjectDto(id = "project", worktree = directory, name = "Project")
                "getSessions" -> {
                    onGetSessions(arguments)
                    sessions()
                }
                "getSession" -> getSession(
                    arguments[0] as String,
                    arguments[1] as String,
                    arguments[2] as String?,
                )
                "getSessionStatus" -> emptyMap<String, SessionStatusDto>()
                "getProviders", "getAgents", "getCommands", "getMcps", "getLsps", "getConfig" -> emptyJson
                "getPermissions", "getQuestions" -> emptyList<Any>()
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(
            OpenCodeApi::class.java.classLoader,
            arrayOf(OpenCodeApi::class.java),
            handler,
        ) as OpenCodeApi
    }

    private fun sessionDto(
        id: String,
        updatedAt: Long = 1L,
        parentId: String? = null,
        directory: String = Companion.directory,
    ) = SessionDto(
        id = id,
        title = id,
        directory = directory,
        parentID = parentId,
        time = SessionTimeDto(created = 1L, updated = updatedAt),
    )

    private class FakeOpenCodeServerRepository(api: OpenCodeApi) : OpenCodeServerRepository {
        private val connection = ServerConnection(
            server = ServerConfig(serverId, "Server", "https://example.test"),
            password = null,
            effectiveBaseUrl = "https://example.test",
            api = api,
        )

        override suspend fun getConnection(serverId: String): ServerConnection = connection

        override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> = emptyList()

        override suspend fun setModelHidden(
            serverId: String,
            providerId: String,
            modelId: String,
            hidden: Boolean,
        ) = Unit
    }

    private companion object {
        const val serverId = "server"
        const val directory = "E:/work/app"
        const val workspace = "workspace-a"
    }
}
