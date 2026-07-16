package io.github.ycfeng.ocdeck.core.network

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

internal class PinnedSshHostKeyRepository(
    expectedFingerprint: String,
) : EmptySshHostKeyRepository() {
    private val expected = SshHostKeyFingerprint.parse(expectedFingerprint)

    @Volatile
    var mismatchObserved: Boolean = false
        private set

    @Volatile
    var matchedSha256Fingerprint: String? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null || key.isEmpty() || !expected.matches(key)) {
            mismatchObserved = true
            return HostKeyRepository.CHANGED
        }

        matchedSha256Fingerprint = SshHostKeyFingerprint.sha256(key)
        return HostKeyRepository.OK
    }
}

internal class DiscoveringSshHostKeyRepository : EmptySshHostKeyRepository() {
    private val capturedKey = AtomicReference<ByteArray?>()

    override fun check(host: String?, key: ByteArray?): Int {
        if (key != null && key.isNotEmpty()) {
            capturedKey.compareAndSet(null, key.copyOf())
        }
        return HostKeyRepository.NOT_INCLUDED
    }

    fun discoveredFingerprint(): String? = capturedKey.get()?.let(SshHostKeyFingerprint::sha256)
}

internal abstract class EmptySshHostKeyRepository : HostKeyRepository {
    final override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    final override fun remove(host: String?, type: String?) = Unit

    final override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    final override fun getKnownHostsRepositoryID(): String = REPOSITORY_ID

    final override fun getHostKey(): Array<HostKey> = emptyArray()

    final override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    private companion object {
        const val REPOSITORY_ID = "OpenCode in-memory SSH host key repository"
    }
}

internal object SshHostKeyFingerprint {
    private val sha256PayloadPattern = Regex("[A-Za-z0-9+/]{43}")
    private val md5BytePattern = Regex("[0-9A-Fa-f]{2}")

    fun parse(value: String): ParsedSshHostKeyFingerprint = when {
        value.startsWith(SHA256_PREFIX) -> parseSha256(value.removePrefix(SHA256_PREFIX))
        value.startsWith(MD5_PREFIX) -> parseMd5(value.removePrefix(MD5_PREFIX))
        else -> throw SshHostKeyFingerprintFormatException()
    }

    fun sha256(rawKey: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM).digest(rawKey)
        return SHA256_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun parseSha256(payload: String): ParsedSshHostKeyFingerprint {
        if (payload.length != SHA256_PAYLOAD_LENGTH || !sha256PayloadPattern.matches(payload)) {
            throw SshHostKeyFingerprintFormatException()
        }
        val digest = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            throw SshHostKeyFingerprintFormatException()
        }
        if (
            digest.size != SHA256_DIGEST_BYTES ||
            Base64.getEncoder().withoutPadding().encodeToString(digest) != payload
        ) {
            throw SshHostKeyFingerprintFormatException()
        }
        return ParsedSshHostKeyFingerprint(SHA256_ALGORITHM, digest)
    }

    private fun parseMd5(payload: String): ParsedSshHostKeyFingerprint {
        if (payload.length != MD5_PAYLOAD_LENGTH) {
            throw SshHostKeyFingerprintFormatException()
        }
        val bytes = payload.split(':')
        if (bytes.size != MD5_DIGEST_BYTES || bytes.any { !md5BytePattern.matches(it) }) {
            throw SshHostKeyFingerprintFormatException()
        }
        val digest = ByteArray(MD5_DIGEST_BYTES) { index ->
            bytes[index].toInt(16).toByte()
        }
        return ParsedSshHostKeyFingerprint(MD5_ALGORITHM, digest)
    }

    private const val SHA256_PREFIX = "SHA256:"
    private const val MD5_PREFIX = "MD5:"
    private const val SHA256_ALGORITHM = "SHA-256"
    private const val MD5_ALGORITHM = "MD5"
    private const val SHA256_DIGEST_BYTES = 32
    private const val MD5_DIGEST_BYTES = 16
    private const val SHA256_PAYLOAD_LENGTH = 43
    private const val MD5_PAYLOAD_LENGTH = 47
}

internal class ParsedSshHostKeyFingerprint(
    private val digestAlgorithm: String,
    private val expectedDigest: ByteArray,
) {
    fun matches(rawKey: ByteArray): Boolean {
        val actualDigest = MessageDigest.getInstance(digestAlgorithm).digest(rawKey)
        return MessageDigest.isEqual(expectedDigest, actualDigest)
    }

    override fun toString(): String = "ParsedSshHostKeyFingerprint(<redacted>)"
}

class SshHostKeyFingerprintFormatException :
    IllegalArgumentException(
        "SSH host fingerprint must use canonical SHA256 or colon-delimited MD5 format",
    )

class SshHostKeyMismatchException(cause: Throwable? = null) :
    SecurityException("SSH host key does not match the configured fingerprint", cause)

class SshHostKeyDiscoveryRequiredException :
    IllegalStateException("SSH host key discovery is required before authentication")

class SshHostKeyPinRequiredException :
    IllegalStateException("A valid SSH host key fingerprint is required before authentication")

class SshHostKeyPinUnavailableException :
    IllegalStateException("The stored SSH host key fingerprint is unavailable")

class SshHostKeyDiscoveryException(cause: Throwable? = null) :
    IllegalStateException("SSH host key discovery failed before authentication", cause)

class SshHostKeyVerificationException(cause: Throwable? = null) :
    SecurityException("SSH host key verification did not complete", cause)
