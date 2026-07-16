package io.github.ycfeng.ocdeck.data.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerOrderTest {
    @Test
    fun reordersServersByIds() {
        val servers = listOf(server("a"), server("b"), server("c"))

        val reordered = reorderServersByIds(servers, listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), reordered.map { it.id })
        assertEquals(servers[2], reordered[0])
    }

    @Test
    fun ignoresUnknownAndDuplicateIds() {
        val servers = listOf(server("a"), server("b"), server("c"))

        val reordered = reorderServersByIds(servers, listOf("missing", "c", "c", "a"))

        assertEquals(listOf("c", "a", "b"), reordered.map { it.id })
    }

    @Test
    fun appendsServersMissingFromRequestedOrder() {
        val servers = listOf(server("a"), server("b"), server("c"), server("d"))

        val reordered = reorderServersByIds(servers, listOf("d", "b"))

        assertEquals(listOf("d", "b", "a", "c"), reordered.map { it.id })
    }

    @Test
    fun keepsCurrentOrderWhenNoValidIdExists() {
        val servers = listOf(server("a"), server("b"), server("c"))

        val reordered = reorderServersByIds(servers, listOf("missing"))

        assertEquals(servers, reordered)
    }

    private fun server(id: String): ServerConfig = ServerConfig(
        id = id,
        name = "Server $id",
        baseUrl = "http://$id.example.test:4096",
    )
}
