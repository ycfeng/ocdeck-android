package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryOpenCodeStoreLifecycleTest {
    @Test
    fun projectWorkspaceAndGlobalServerStatusesAreIsolated() {
        val store = InMemoryOpenCodeStore(PathNormalizer())

        store.setProjectConnectionStatus(
            serverId = "server",
            directory = "E:\\work\\app\\",
            status = SseConnectionStatus.Open,
            workspace = "E:\\workspaces\\one\\",
        )
        store.setProjectConnectionStatus(
            serverId = "server",
            directory = "E:/work/app",
            status = SseConnectionStatus.Failed(SseFailureReason.InvalidResponse),
            workspace = "E:/workspaces/two",
        )
        store.setGlobalConnectionStatus("one", SseConnectionStatus.Open)
        store.setGlobalConnectionStatus(
            "two",
            SseConnectionStatus.Retrying(1, 1_000L, SseFailureReason.NetworkUnavailable),
        )

        assertEquals(
            SseConnectionStatus.Open,
            store.currentProject("server", "e:/WORK/app", "e:/WORKSPACES/one").connectionStatus,
        )
        assertEquals(
            SseConnectionStatus.Failed(SseFailureReason.InvalidResponse),
            store.currentProject("server", "E:/work/app", "E:/workspaces/two").connectionStatus,
        )
        assertEquals(SseConnectionStatus.Open, store.currentGlobalConnectionStatus("one"))
        assertEquals(
            SseConnectionStatus.Retrying(1, 1_000L, SseFailureReason.NetworkUnavailable),
            store.currentGlobalConnectionStatus("two"),
        )
    }

    @Test
    fun nonWindowsWorkspaceRemainsCaseSensitive() {
        val store = InMemoryOpenCodeStore(PathNormalizer())
        store.setProjectConnectionStatus("server", "/repo", SseConnectionStatus.Open, workspace = "Workspace-A")

        assertEquals(SseConnectionStatus.Open, store.currentProject("server", "/repo", "Workspace-A").connectionStatus)
        assertEquals(SseConnectionStatus.Closed, store.currentProject("server", "/repo", "workspace-a").connectionStatus)
    }
}
