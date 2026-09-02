package io.buildthisplease.core

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface IntegrityTokenProvider {
    suspend fun token(cloudProjectNumber: Long, requestHash: String): String
}

class GooglePlayIntegrityTokenProvider(context: Context) : IntegrityTokenProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()
    private var prepared: Pair<Long, StandardIntegrityManager.StandardIntegrityTokenProvider>? = null

    override suspend fun token(cloudProjectNumber: Long, requestHash: String): String {
        val provider = mutex.withLock {
            prepared?.takeIf { it.first == cloudProjectNumber }?.second ?: prepare(cloudProjectNumber).also {
                prepared = cloudProjectNumber to it
            }
        }
        return try {
            provider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        } catch (failure: Throwable) {
            mutex.withLock { prepared = null }
            throw BuildThisPleaseException.IntegrityUnavailable(failure)
        }
    }

    private suspend fun prepare(cloudProjectNumber: Long) = manager.prepareIntegrityToken(
        PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
    ).await()
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
