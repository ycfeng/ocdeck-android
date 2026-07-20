package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropProcessSupportTest {
    @Test
    fun redactorUsesCanonicalPlaceholderForSensitiveValuesAndUrls() {
        val secret = "interop-secret-value"
        val privatePath = "C:\\private\\interop"
        val privateUrl = "https://user:password@example.invalid/private?token=value"
        val redactor = InteropRedactor(secret, privatePath)

        val redacted = redactor.redact(
            "token=$secret path=C:/private/interop endpoint=$privateUrl cookie=session-value",
        )

        assertEquals(
            "token=<redacted> path=<redacted> endpoint=<redacted> cookie=<redacted>",
            redacted,
        )
        assertFalse(redacted.contains(secret))
        assertFalse(redacted.contains("example.invalid"))
    }

    @Test
    fun oversizedDiagnosticDoesNotLeakSensitivePrefix() {
        val secret = "prefix-that-must-not-leak"
        val raw = "token=$secret " + "x".repeat(20 * 1024)

        val redacted = InteropRedactor(secret).redact(raw)

        assertEquals("<redacted>", redacted)
        assertFalse(redacted.contains(secret))
    }

    @Test
    fun privateFileUsesOwnerOnlyPosixPermissionsAndCheckedDeletion() = withTemporaryDirectory { root ->
        val privateDirectory = root.resolve("private")
        createPrivateDirectory(privateDirectory)
        val privateFile = privateDirectory.resolve("frpc.toml")

        writePrivateFile(privateFile, "token=<redacted>")

        assertTrue(Files.isRegularFile(privateFile, LinkOption.NOFOLLOW_LINKS))
        if (Files.getFileStore(privateFile).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(privateFile, LinkOption.NOFOLLOW_LINKS),
            )
        }
        deleteTreeChecked(root)
        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun checkedDeletionDoesNotFollowSymbolicLinks() {
        val root = Files.createTempDirectory("frp-interop-delete-test-")
        val externalRoot = Files.createTempDirectory("frp-interop-delete-external-")
        try {
            val externalFile = externalRoot.resolve("keep.txt")
            Files.write(externalFile, byteArrayOf(1), StandardOpenOption.CREATE_NEW)
            try {
                Files.createSymbolicLink(root.resolve("external"), externalRoot)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: FileSystemException) {
                return
            } catch (_: SecurityException) {
                return
            }

            deleteTreeChecked(root)

            assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(externalFile, LinkOption.NOFOLLOW_LINKS))
        } finally {
            deleteTreeBestEffort(root)
            deleteTreeBestEffort(externalRoot)
        }
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("frp-interop-process-test-")
        try {
            block(root)
        } finally {
            deleteTreeBestEffort(root)
        }
    }
}
