package io.github.ycfeng.ocdeck.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderCapabilityRefreshTest {
    @Test
    fun providerMutationRefreshUsesCurrentOpenProjectAuthority() = runTest {
        val serverId = "server"
        val directory = "E:/work/app"
        val workspace = "workspace"
        val factory = RecordingSseEventSourceFactory()
        val loader = ScriptedProjectSnapshotLoader { loadedServerId, loadedDirectory, loadedWorkspace, _ ->
            Result.success(loadedSnapshot(loadedServerId, loadedDirectory, loadedWorkspace))
        }
        val environment = eventClientEnvironment(
            scope = backgroundScope,
            factory = factory,
            loader = loader,
        )
        environment.client.connectProject(serverId, directory, workspace)
        runCurrent()
        factory.sources.single().open()
        runCurrent()
        assertEquals(1, loader.calls)

        environment.client.refreshProviderCapabilities(serverId)
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(workspace, environment.store.currentProject(serverId, directory, workspace).workspace)
    }
}
