package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.network.MaxFileContentDecodedResponseBytes
import io.github.ycfeng.ocdeck.core.network.OpenCodeApi
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeRequestException
import io.github.ycfeng.ocdeck.core.network.PathInfoDto
import io.github.ycfeng.ocdeck.core.network.ProjectDto
import io.github.ycfeng.ocdeck.core.network.RetrofitInboundResponseTooLargeException
import io.github.ycfeng.ocdeck.core.network.SessionStatusDto
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.data.server.OpenCodeServerRepository
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerConnection
import io.github.ycfeng.ocdeck.data.server.ServerHiddenModelPreference
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class OpenCodeRepositoryFailureHandlingTest {
    @Test
    fun currentProject404FallsBackToNull() = runTest {
        val repository = repository(
            fakeApi(currentProject = { throw httpException(404) }),
        )

        val snapshot = repository.loadProject(SERVER_ID, DIRECTORY, SessionListWindowState()).getOrThrow()

        assertNull(snapshot.project)
    }

    @Test
    fun currentProjectNon404HttpFailuresPropagate() = runTest {
        listOf(401, 503).forEach { statusCode ->
            val repository = repository(
                fakeApi(currentProject = { throw httpException(statusCode) }),
            )

            val failure = repository.loadProject(SERVER_ID, DIRECTORY, SessionListWindowState()).exceptionOrNull()

            assertTrue(failure is OpenCodeRequestException)
            assertEquals(OpenCodeFailure.HttpStatus(statusCode), (failure as OpenCodeRequestException).failure)
        }
    }

    @Test
    fun currentProjectInvalidAndOversizedResponsesPropagate() = runTest {
        val cases = listOf(
            SerializationException("invalid JSON") to OpenCodeFailure.InvalidResponse,
            RetrofitInboundResponseTooLargeException() to OpenCodeFailure.ResponseTooLarge,
        )

        cases.forEach { (currentProjectFailure, expectedFailure) ->
            val repository = repository(
                fakeApi(currentProject = { throw currentProjectFailure }),
            )

            val failure = repository.loadProject(SERVER_ID, DIRECTORY, SessionListWindowState()).exceptionOrNull()

            assertTrue(failure is OpenCodeRequestException)
            assertEquals(expectedFailure, (failure as OpenCodeRequestException).failure)
        }
    }

    @Test
    fun currentProjectCancellationAndJvmErrorPropagateUnchanged() = runTest {
        val cancellation = CancellationException("cancelled")
        try {
            repository(fakeApi(currentProject = { throw cancellation })).loadProject(
                SERVER_ID,
                DIRECTORY,
                SessionListWindowState(),
            )
            fail("CancellationException was not propagated")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation || actual.cause === cancellation)
        }

        val fatal = TestJvmError()
        try {
            repository(fakeApi(currentProject = { throw fatal })).loadProject(
                SERVER_ID,
                DIRECTORY,
                SessionListWindowState(),
            )
            fail("JVM Error was not propagated")
        } catch (actual: TestJvmError) {
            assertSame(fatal, actual)
        }
    }

    @Test
    fun projectFileMapsAllInboundLimitFailuresToTooLarge() = runTest {
        listOf(
            DeclaredLengthResponseBody(MaxFileContentDecodedResponseBytes + 1L),
            ThrowingResponseBody(RetrofitInboundResponseTooLargeException()),
        ).forEach { responseBody ->
            val repository = repository(
                fakeApi(fileContent = { responseBody }),
            )

            val content = repository.loadProjectFile(SERVER_ID, DIRECTORY, FILE_PATH).getOrThrow()

            assertEquals(OpenCodeFileContent.TooLarge(FILE_PATH), content)
        }
    }

    private fun repository(api: OpenCodeApi): OpenCodeRepository = OpenCodeRepository(
        serverRepository = FakeOpenCodeServerRepository(api),
        pathNormalizer = PathNormalizer(),
        projectFilePathNormalizer = ProjectFilePathNormalizer(),
        json = Json { ignoreUnknownKeys = true },
        redactor = Redactor(),
    )

    private fun fakeApi(
        currentProject: () -> ProjectDto = {
            ProjectDto(id = "project", worktree = DIRECTORY, name = "Project")
        },
        fileContent: () -> ResponseBody = {
            """{"type":"text","content":"hello"}""".toResponseBody()
        },
    ): OpenCodeApi {
        val emptyJson = JsonObject(emptyMap())
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "toString" -> "FakeOpenCodeApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "getPath" -> PathInfoDto(directory = DIRECTORY)
                "getCurrentProject" -> currentProject()
                "getSessions" -> emptyList<Any>()
                "getSessionStatus" -> emptyMap<String, SessionStatusDto>()
                "getProviders", "getAgents", "getCommands", "getMcps", "getLsps", "getConfig" -> emptyJson
                "getPermissions", "getQuestions" -> emptyList<Any>()
                "getFileContent" -> fileContent()
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(
            OpenCodeApi::class.java.classLoader,
            arrayOf(OpenCodeApi::class.java),
            handler,
        ) as OpenCodeApi
    }

    private fun httpException(code: Int): HttpException {
        val rawResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.test/project/current").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .build()
        return HttpException(Response.error<Unit>(ByteArray(0).toResponseBody(), rawResponse))
    }

    private class DeclaredLengthResponseBody(
        private val length: Long,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = length

        override fun source(): BufferedSource = Buffer()
    }

    private class ThrowingResponseBody(
        private val failure: RetrofitInboundResponseTooLargeException,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = throw failure
    }

    private class FakeOpenCodeServerRepository(api: OpenCodeApi) : OpenCodeServerRepository {
        private val connection = ServerConnection(
            server = ServerConfig(SERVER_ID, "Server", "https://example.test"),
            password = null,
            effectiveBaseUrl = "https://example.test",
            api = api,
        )

        override suspend fun getConnection(serverId: String): ServerConnection = connection

        override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> =
            emptyList()

        override suspend fun setModelHidden(
            serverId: String,
            providerId: String,
            modelId: String,
            hidden: Boolean,
        ) = Unit
    }

    private class TestJvmError : Error()

    private companion object {
        const val SERVER_ID = "server"
        const val DIRECTORY = "E:/work/app"
        const val FILE_PATH = "src/Main.kt"
    }
}
