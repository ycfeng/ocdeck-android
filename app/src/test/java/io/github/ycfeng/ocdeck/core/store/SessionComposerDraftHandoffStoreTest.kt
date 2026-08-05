package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComposerDraftHandoffStoreTest {
    private val store = SessionComposerDraftHandoffStore(PathNormalizer())

    @Test
    fun consumeUsesNormalizedKeyAndRemovesDraftOnce() {
        val draft = draft()
        store.put("server", "E:\\Repo\\", "D:\\Workspace\\", "ses_fork", draft)

        assertEquals(draft, store.consume("server", "e:/repo", "d:/workspace", "ses_fork"))
        assertNull(store.consume("server", "E:/Repo", "D:/Workspace", "ses_fork"))
    }

    @Test
    fun workspaceAndSessionKeysRemainIsolated() {
        val draft = draft()
        store.put("server", "E:/Repo", "D:/Workspace", "ses_fork", draft)

        assertNull(store.consume("server", "E:/Repo", null, "ses_fork"))
        assertNull(store.consume("server", "E:/Repo", "D:/Other", "ses_fork"))
        assertNull(store.consume("server", "E:/Repo", "D:/Workspace", "ses_other"))
        assertEquals(draft, store.consume("server", "E:/Repo", "D:/Workspace", "ses_fork"))
    }

    @Test
    fun structuralSummaryDoesNotExposePromptPathOrSelections() {
        val draft = draft()
        val summary = draft.toString()

        assertFalse(summary.contains("secret prompt"))
        assertFalse(summary.contains("src/Secret.kt"))
        assertFalse(summary.contains("provider/model"))
        assertTrue(summary.contains("textLength="))
        assertTrue(summary.contains("projectFileContextCount=1"))
    }

    private fun draft() = SessionComposerDraftHandoff(
        text = "secret prompt",
        attachments = emptyList(),
        projectFileContexts = listOf(ProjectFileContext("prt_1", "src/Secret.kt")),
        selectedAgentId = "build",
        selectedModel = PromptModelSelection("provider", "model"),
        selectedVariant = "high",
    )
}
