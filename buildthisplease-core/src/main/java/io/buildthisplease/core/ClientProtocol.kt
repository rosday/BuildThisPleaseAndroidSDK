package io.buildthisplease.core

interface BuildThisPleaseClientProtocol {
    suspend fun configuration(): ProjectConfiguration
    suspend fun requests(): List<Ticket>
    suspend fun myRequests(): List<Ticket>
    suspend fun implementedRequests(): List<Ticket>
    suspend fun ticket(id: String): Ticket
    suspend fun comments(ticketId: String): List<Comment>
    suspend fun createTicket(title: String, description: String, email: String? = null, idempotencyKey: String = newIdempotencyKey()): Ticket
    suspend fun setVote(ticketId: String, voted: Boolean): Ticket
    suspend fun createComment(ticketId: String, body: String, idempotencyKey: String = newIdempotencyKey()): Comment
    suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment
    suspend fun updateSubscriptionStatus(status: SubscriptionStatus)
    suspend fun updateUserIdentity(revenueCatAppUserId: String?, email: String? = null)
}

fun newIdempotencyKey(): String = java.util.UUID.randomUUID().toString()
