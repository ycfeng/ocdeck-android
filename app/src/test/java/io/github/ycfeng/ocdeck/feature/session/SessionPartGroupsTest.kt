package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPartGroupsTest {
    @Test
    fun groupsConsecutiveContextToolsAndSplitsOnOtherParts() {
        val groups = groupAssistantParts(
            listOf(
                part(id = "prt1", tool = "read"),
                part(id = "prt2", tool = "glob"),
                part(id = "prt3", tool = "websearch"),
                part(id = "prt4", tool = "grep"),
                part(id = "prt5", type = "text", tool = null, text = "done"),
            ),
        )

        assertEquals(3, groups.size)
        assertEquals(listOf("read", "glob"), (groups[0] as AssistantPartGroup.Context).parts.map { it.tool })
        assertEquals(listOf("grep"), (groups[1] as AssistantPartGroup.Context).parts.map { it.tool })
        assertEquals("done", (groups[2] as AssistantPartGroup.Text).part.text)
    }

    @Test
    fun groupsShellToolAsStandaloneRenderablePart() {
        val groups = groupAssistantParts(
            listOf(
                part(id = "prt1", tool = "read"),
                part(id = "prt2", tool = "bash", input = mapOf("command" to "playwright-cli response-body 37")),
                part(id = "prt3", tool = "grep"),
            ),
        )

        assertEquals(3, groups.size)
        assertEquals(listOf("read"), (groups[0] as AssistantPartGroup.Context).parts.map { it.tool })
        assertEquals("playwright-cli response-body 37", (groups[1] as AssistantPartGroup.Shell).part.toolInput["command"])
        assertEquals(listOf("grep"), (groups[2] as AssistantPartGroup.Context).parts.map { it.tool })
    }

    @Test
    fun groupsPatchSkillAndGenericToolsAsStandaloneRenderableParts() {
        val groups = groupAssistantParts(
            listOf(
                part(id = "prt1", tool = "read"),
                part(id = "prt2", tool = "apply_patch"),
                part(id = "prt3", tool = "skill", input = mapOf("name" to "playwright-cli")),
                part(id = "prt4", tool = "compress", input = mapOf("topic" to "Shell 工具实现")),
                part(id = "prt5", tool = "grep"),
            ),
        )

        assertEquals(5, groups.size)
        assertEquals(listOf("read"), (groups[0] as AssistantPartGroup.Context).parts.map { it.tool })
        assertEquals("apply_patch", (groups[1] as AssistantPartGroup.Patch).part.tool)
        assertEquals("skill", (groups[2] as AssistantPartGroup.Skill).part.tool)
        assertEquals("compress", (groups[3] as AssistantPartGroup.GenericTool).part.tool)
        assertEquals(listOf("grep"), (groups[4] as AssistantPartGroup.Context).parts.map { it.tool })
    }

    @Test
    fun contextSummaryCountsReadGlobGrepAndListOnly() {
        val summary = contextToolSummary(
            listOf(
                part(id = "read1", tool = "read"),
                part(id = "glob1", tool = "glob"),
                part(id = "grep1", tool = "grep"),
                part(id = "list1", tool = "list"),
                part(id = "web1", tool = "websearch"),
            ),
        )

        assertEquals(1, summary.read)
        assertEquals(2, summary.search)
        assertEquals(1, summary.list)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun pendingUsesToolStateOrBusyTurn() {
        val completed = AssistantPartGroup.Context(listOf(part(id = "prt1", tool = "read", stateStatus = "completed")))
        val running = AssistantPartGroup.Context(listOf(part(id = "prt2", tool = "grep", stateStatus = "running")))

        assertFalse(completed.isPending(forceBusy = false))
        assertTrue(completed.isPending(forceBusy = true))
        assertTrue(running.isPending(forceBusy = false))
    }

    @Test
    fun renderablePartRequiresTextOrContextTool() {
        assertTrue(hasRenderableSessionPart(listOf(part(id = "prt1", tool = "read"))))
        assertTrue(hasRenderableSessionPart(listOf(part(id = "prt1b", tool = "bash"))))
        assertTrue(hasRenderableSessionPart(listOf(part(id = "prt1c", tool = "apply_patch"))))
        assertTrue(hasRenderableSessionPart(listOf(part(id = "prt1d", tool = "skill"))))
        assertTrue(hasRenderableSessionPart(listOf(part(id = "prt1e", tool = "compress"))))
        assertTrue(hasRenderableSessionPart(listOf(part(id = "prt2", type = "text", tool = null, text = "hello"))))
        assertFalse(hasRenderableSessionPart(listOf(part(id = "prt3", tool = "websearch"))))
        assertFalse(hasRenderableSessionPart(listOf(part(id = "prt4", type = "text", tool = null, text = "", synthetic = true))))
    }

    @Test
    fun contextToolDisplayBuildsLightweightToolMetadata() {
        val read = contextToolDisplay(
            part(
                id = "prt1",
                tool = "read",
                stateStatus = "completed",
                input = mapOf("filePath" to "X:/workspace/sample-project/src/Main.kt", "offset" to "10", "limit" to "20"),
            ),
        )
        val grep = contextToolDisplay(
            part(
                id = "prt2",
                tool = "grep",
                stateStatus = "pending",
                input = mapOf("path" to "/workspace/sample-project", "pattern" to "ContextTool", "include" to "*.kt"),
            ),
        )

        assertEquals(UiText.Resource(R.string.tool_read), read.title)
        assertEquals("Main.kt", read.subtitle)
        assertEquals(listOf("offset=10", "limit=20"), read.args)
        assertFalse(read.running)
        assertEquals(UiText.Raw("Grep"), grep.title)
        assertEquals("/workspace/sample-project", grep.subtitle)
        assertEquals(listOf("pattern=ContextTool", "include=*.kt"), grep.args)
        assertTrue(grep.running)
    }

    @Test
    fun shellToolDisplayUsesCommandOutputMetadataAndError() {
        val completed = shellToolDisplay(
            part(
                id = "prt1",
                tool = "bash",
                stateStatus = "completed",
                input = mapOf("command" to "playwright-cli response-body 37"),
                output = "line1\r\nline2",
                metadata = mapOf("output" to "metadata output"),
            ),
        )
        val running = shellToolDisplay(
            part(
                id = "prt2",
                tool = "bash",
                stateStatus = "running",
                input = mapOf("command" to "npm test"),
                metadata = mapOf("output" to "partial output"),
            ),
        )
        val error = shellToolDisplay(
            part(
                id = "prt3",
                tool = "bash",
                stateStatus = "error",
                input = mapOf("command" to "bad-command"),
                error = "command not found",
            ),
        )

        assertEquals("playwright-cli response-body 37", completed.command)
        assertEquals("line1\nline2", completed.output)
        assertEquals("$ playwright-cli response-body 37\n\nline1\nline2", completed.copyText)
        assertFalse(completed.pending)
        assertEquals("partial output", running.output)
        assertTrue(running.pending)
        assertEquals("command not found", error.copyText)
    }

    @Test
    fun patchToolDisplayParsesMetadataFiles() {
        val display = patchToolDisplay(
            part(
                id = "prt1",
                tool = "apply_patch",
                metadataJson = """
                    {
                      "files": [
                        {
                          "filePath": "X:/workspace/sample-project/app/src/SessionPartGroups.kt",
                          "relativePath": "app/src/SessionPartGroups.kt",
                          "type": "update",
                          "diff": "@@\r\n+line",
                          "additions": 7,
                          "deletions": 2
                        },
                        {
                          "filePath": "X:/workspace/sample-project/index.html",
                          "relativePath": "横版打飞机.html",
                          "type": "move",
                          "movePath": "X:/workspace/sample-project/横版打飞机.html",
                          "additions": 0,
                          "deletions": 0
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(UiText.Resource(R.string.tool_file_count, listOf(2)), display.fileCountLabel)
        assertEquals(2, display.files.size)
        assertEquals("SessionPartGroups.kt", display.files[0].fileName)
        assertEquals("app/src/", display.files[0].directory)
        assertEquals(7, display.files[0].additions)
        assertEquals(2, display.files[0].deletions)
        assertEquals("@@\n+line", display.files[0].diff)
        assertEquals(UiText.Resource(R.string.tool_file_moved), display.files[1].actionLabel)
        assertNull(display.files[1].directory)
    }

    @Test
    fun skillAndGenericToolDisplayFollowWebTitleAndArgsRules() {
        val skill = skillToolDisplay(
            part(
                id = "prt1",
                tool = "skill",
                input = mapOf("name" to "playwright-cli"),
                inputJson = """{"name":"playwright-cli"}""",
            ),
        )
        val generic = genericToolDisplay(
            part(
                id = "prt2",
                tool = "compress",
                input = mapOf("topic" to "Shell 工具实现", "content" to "[]"),
                inputJson = """{"topic":"Shell 工具实现","content":[{"startId":"m1"}],"count":3}""",
            ),
        )

        assertEquals(UiText.Raw("Playwright-Cli"), skill.title)
        assertFalse(skill.pending)
        assertEquals(UiText.Resource(R.string.tool_called, listOf("compress")), generic.title)
        assertNull(generic.subtitle)
        assertEquals(listOf("topic=Shell 工具实现", "count=3"), generic.args)
    }

    @Test
    fun applyPatchErrorUsesToolErrorDisplay() {
        val groups = groupAssistantParts(
            listOf(part(id = "prt1", tool = "apply_patch", stateStatus = "error", error = "Tool execution aborted")),
        )
        val display = toolErrorDisplay((groups.single() as AssistantPartGroup.ToolError).part)

        assertEquals(UiText.Resource(R.string.tool_patch), display.title)
        assertEquals(UiText.Resource(R.string.tool_failed), display.subtitle)
        assertEquals("Tool execution aborted", display.body)
        assertEquals("Tool execution aborted", display.copyText)
    }

    @Test
    fun completedQuestionToolDisplaysAnsweredCard() {
        val part = part(
            id = "prt1",
            tool = "question",
            inputJson = """
                {
                  "questions": [
                    {
                      "header": "请选择方向",
                      "question": "你想让我生成哪类三个选项？",
                      "options": [
                        { "label": "方案选项", "description": "围绕某个任务给出三个实施方案。" }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            metadataJson = """
                {
                  "answers": [["方案选项"]]
                }
            """.trimIndent(),
        )

        val groups = groupAssistantParts(listOf(part))
        val display = questionToolDisplay((groups.single() as AssistantPartGroup.Question).part)

        assertEquals(UiText.Resource(R.string.question_answered_count, listOf(1)), display.subtitle)
        assertTrue(display.defaultOpen)
        assertFalse(display.dismissed)
        assertEquals(UiText.Raw("你想让我生成哪类三个选项？"), display.answers.single().question)
        assertEquals(UiText.Raw("方案选项"), display.answers.single().answer)
    }

    @Test
    fun pendingQuestionToolIsHiddenFromMessageStream() {
        val groups = groupAssistantParts(
            listOf(part(id = "prt1", tool = "question", stateStatus = "running")),
        )

        assertTrue(groups.isEmpty())
        assertFalse(hasRenderableSessionPart(listOf(part(id = "prt2", tool = "question", stateStatus = "pending"))))
    }

    @Test
    fun dismissedQuestionToolUsesWeakQuestionCard() {
        val groups = groupAssistantParts(
            listOf(part(id = "prt1", tool = "question", stateStatus = "error", error = "The user dismissed this question")),
        )
        val display = questionToolDisplay((groups.single() as AssistantPartGroup.Question).part)

        assertEquals(UiText.Resource(R.string.question_dismissed), display.subtitle)
        assertTrue(display.dismissed)
        assertTrue(display.answers.isEmpty())
    }

    private fun part(
        id: String,
        type: String = "tool",
        tool: String? = "read",
        text: String? = null,
        synthetic: Boolean = false,
        stateStatus: String = "completed",
        input: Map<String, String> = emptyMap(),
        inputJson: String? = null,
        metadata: Map<String, String> = emptyMap(),
        metadataJson: String? = null,
        output: String? = null,
        error: String? = null,
    ): OpenCodeMessagePart = OpenCodeMessagePart(
        id = id,
        messageId = "msg1",
        sessionId = "ses1",
        type = type,
        text = text,
        synthetic = synthetic,
        tool = tool,
        stateStatus = stateStatus,
        toolInput = input,
        toolInputJson = inputJson,
        toolMetadata = metadata,
        toolMetadataJson = metadataJson,
        toolOutput = output,
        toolError = error,
    )
}
