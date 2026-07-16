package io.github.ycfeng.ocdeck.core.network

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SshHostKeyRepositoryTest {
    @Test
    fun canonicalSha256FingerprintMatchesRawHostKey() {
        val rawKey = "ssh-host-key-blob".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawKey)
        val repository = PinnedSshHostKeyRepository(fingerprint)

        assertTrue(fingerprint.startsWith("SHA256:"))
        assertFalse(fingerprint.contains('='))
        assertEquals(HostKeyRepository.OK, repository.check("ssh.example.test", rawKey))
        assertEquals(fingerprint, repository.matchedSha256Fingerprint)
        assertFalse(repository.mismatchObserved)
    }

    @Test
    fun sha256Base64ComparisonIsCaseSensitive() {
        val rawKey = "case-sensitive-host-key".toByteArray()
        val canonical = SshHostKeyFingerprint.sha256(rawKey)
        val changedCase = canonical.replaceFirstAlphabeticPayloadCharacter()
        val repository = PinnedSshHostKeyRepository(changedCase)

        assertEquals(HostKeyRepository.CHANGED, repository.check("ssh.example.test", rawKey))
        assertTrue(repository.mismatchObserved)
        assertNull(repository.matchedSha256Fingerprint)
    }

    @Test
    fun mismatchReturnsChangedWithoutExposingFingerprints() {
        val expected = SshHostKeyFingerprint.sha256("expected-key".toByteArray())
        val actual = "actual-key".toByteArray()
        val repository = PinnedSshHostKeyRepository(expected)

        assertEquals(HostKeyRepository.CHANGED, repository.check("ssh.example.test", actual))
        assertFalse(repository.toString().contains(expected))
    }

    @Test
    fun strictLegacyMd5FingerprintMatchesRawHostKey() {
        val rawKey = "legacy-host-key".toByteArray()
        val md5 = MessageDigest.getInstance("MD5").digest(rawKey)
            .joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val repository = PinnedSshHostKeyRepository("MD5:$md5")

        assertEquals(HostKeyRepository.OK, repository.check("ssh.example.test", rawKey))
        assertEquals(SshHostKeyFingerprint.sha256(rawKey), repository.matchedSha256Fingerprint)
    }

    @Test
    fun malformedAndUnknownFingerprintsAreRejectedStrictly() {
        val invalidFingerprints = listOf(
            "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "SHA1:AAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA_",
            " SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "MD5:00:11:22",
            "MD5:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:gg",
        )

        invalidFingerprints.forEach { fingerprint ->
            val failure = assertThrows(SshHostKeyFingerprintFormatException::class.java) {
                PinnedSshHostKeyRepository(fingerprint)
            }
            assertFalse(failure.message.orEmpty().contains(fingerprint))
        }
    }

    @Test
    fun discoveryCapturesKeyButAlwaysReturnsNotIncluded() {
        val rawKey = "discovered-host-key".toByteArray()
        val repository = DiscoveringSshHostKeyRepository()

        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("ssh.example.test", rawKey))
        assertEquals(SshHostKeyFingerprint.sha256(rawKey), repository.discoveredFingerprint())
    }

    @Test
    fun repositoryMetadataIsEmptyAndNonSensitive() {
        val fingerprint = SshHostKeyFingerprint.sha256("host-key".toByteArray())
        val repository = PinnedSshHostKeyRepository(fingerprint)

        assertNotNull(repository.getHostKey())
        assertNotNull(repository.getHostKey("ssh.example.test", "ssh-ed25519"))
        assertTrue(repository.getHostKey().isEmpty())
        assertTrue(repository.getHostKey("ssh.example.test", null).isEmpty())
        assertFalse(repository.knownHostsRepositoryID.contains("ssh.example.test"))
        assertFalse(repository.knownHostsRepositoryID.contains(fingerprint))
    }
}

private fun String.replaceFirstAlphabeticPayloadCharacter(): String {
    val prefixLength = indexOf(':') + 1
    val index = (prefixLength until length).first { this[it].isLetter() }
    val replacement = if (this[index].isUpperCase()) this[index].lowercaseChar() else this[index].uppercaseChar()
    return replaceRange(index, index + 1, replacement.toString())
}
