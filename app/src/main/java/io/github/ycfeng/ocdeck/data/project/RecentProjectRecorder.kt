package io.github.ycfeng.ocdeck.data.project

import androidx.datastore.core.CorruptionException
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.ArrayDeque

class RecentProjectRecorder(
    private val repository: RecentProjectRepository,
    private val pathNormalizer: PathNormalizer,
    scope: CoroutineScope,
    private val failureReporter: RecentProjectFailureReporter,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val queueCapacity = queueCapacity.also { require(it > 0) }
    private val queueLock = Any()
    private val queue = ArrayDeque<RecordRequest>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    internal val workerJob: Job = scope.launch { consumeQueue() }

    fun recordAdd(serverId: String, directory: String) {
        val normalized = pathNormalizer.normalize(directory)
        enqueue(
            AddRequest(
                serverId = serverId,
                directory = normalized,
                comparisonDirectory = pathNormalizer.comparisonKey(normalized),
            ),
        )
    }

    fun recordUpsert(serverId: String, project: ProjectRef) {
        val normalized = pathNormalizer.normalize(project.normalizedDirectory)
        enqueue(
            UpsertRequest(
                serverId = serverId,
                project = project.copy(normalizedDirectory = normalized),
                comparisonDirectory = pathNormalizer.comparisonKey(normalized),
            ),
        )
    }

    private fun enqueue(request: RecordRequest) {
        val accepted = synchronized(queueLock) {
            val previous = queue.peekLast()
            when {
                previous?.isSameProject(request) == true -> {
                    queue.removeLast()
                    queue.addLast(previous.merge(request))
                    true
                }
                queue.size >= queueCapacity -> false
                else -> {
                    queue.addLast(request)
                    true
                }
            }
        }
        if (accepted) {
            queueSignal.trySend(Unit)
        } else {
            failureReporter.reportSafely(RecentProjectFailure.QueueFull)
        }
    }

    private suspend fun consumeQueue() {
        for (ignored in queueSignal) {
            while (true) {
                val request = synchronized(queueLock) { queue.pollFirst() } ?: break
                persist(request)
            }
        }
    }

    private suspend fun persist(request: RecordRequest) {
        var ioAttempts = 0
        while (true) {
            try {
                when (request) {
                    is AddRequest -> repository.add(request.serverId, request.directory)
                    is UpsertRequest -> repository.upsert(request.serverId, request.project)
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Error) {
                throw error
            } catch (_: SerializationException) {
                failureReporter.reportSafely(RecentProjectFailure.WriteSerialization)
                return
            } catch (_: CorruptionException) {
                failureReporter.reportSafely(RecentProjectFailure.WriteCorruption)
                return
            } catch (_: IOException) {
                if (ioAttempts++ == 0) continue
                failureReporter.reportSafely(RecentProjectFailure.WriteIo)
                return
            } catch (_: Exception) {
                failureReporter.reportSafely(RecentProjectFailure.WriteUnexpected)
                return
            }
        }
    }

    private sealed class RecordRequest(
        open val serverId: String,
        open val comparisonDirectory: String,
    ) {
        fun isSameProject(other: RecordRequest): Boolean =
            serverId == other.serverId && comparisonDirectory == other.comparisonDirectory

        fun merge(newer: RecordRequest): RecordRequest = when {
            newer is UpsertRequest -> newer
            this is UpsertRequest -> this
            else -> newer
        }
    }

    private class AddRequest(
        override val serverId: String,
        val directory: String,
        override val comparisonDirectory: String,
    ) : RecordRequest(serverId, comparisonDirectory) {
        override fun toString(): String = "AddRequest(serverId=<redacted>, directory=<redacted>)"
    }

    private class UpsertRequest(
        override val serverId: String,
        val project: ProjectRef,
        override val comparisonDirectory: String,
    ) : RecordRequest(serverId, comparisonDirectory) {
        override fun toString(): String = "UpsertRequest(serverId=<redacted>, project=$project)"
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64
    }
}
