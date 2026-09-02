package io.buildthisplease.compose

import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Comment
import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import io.buildthisplease.core.newIdempotencyKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FeedbackSection { REQUESTS, MINE, IMPLEMENTED }

data class FeedbackUiState(
    val section: FeedbackSection = FeedbackSection.REQUESTS,
    val requests: List<Ticket> = emptyList(),
    val mine: List<Ticket> = emptyList(),
    val implemented: List<Ticket> = emptyList(),
    val selectedTicket: Ticket? = null,
    val comments: List<Comment> = emptyList(),
    val isLoading: Boolean = false,
    val isActing: Boolean = false,
    val votingTicketIds: Set<String> = emptySet(),
    val error: String? = null,
) {
    val currentTickets: List<Ticket> get() = when (section) {
        FeedbackSection.REQUESTS -> requests
        FeedbackSection.MINE -> mine
        FeedbackSection.IMPLEMENTED -> implemented
    }
}

class FeedbackController internal constructor(
    private val client: BuildThisPleaseClientProtocol,
    initialSection: FeedbackSection = FeedbackSection.REQUESTS,
) {
    private val mutableState = MutableStateFlow(FeedbackUiState(section = initialSection))
    private var detailRequestId: String? = null
    val state: StateFlow<FeedbackUiState> = mutableState.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        mutableState.update { it.copy(isLoading = true) }
        runCatching {
            coroutineScope {
                val requests = async { client.requests() }
                val mine = async { client.myRequests() }
                val implemented = async { client.implementedRequests() }
                Triple(requests.await(), mine.await(), implemented.await())
            }
        }.onSuccess { (requests, mine, implemented) ->
            mutableState.update {
                it.copy(
                    requests = requests.sortedForRequests(),
                    mine = mine,
                    implemented = implemented.sortedForImplemented(),
                    isLoading = false,
                    error = null,
                )
            }
        }.onFailure { failure -> mutableState.update { it.copy(isLoading = false, error = failure.displayMessage()) } }
    }

    fun selectSection(section: FeedbackSection) = mutableState.update { it.copy(section = section) }
    fun clearError() = mutableState.update { it.copy(error = null) }

    suspend fun refreshCurrentSection() {
        if (state.value.isLoading) return
        val section = state.value.section
        mutableState.update { it.copy(isLoading = true) }
        runCatching {
            when (section) {
                FeedbackSection.REQUESTS -> client.requests().sortedForRequests()
                FeedbackSection.MINE -> client.myRequests()
                FeedbackSection.IMPLEMENTED -> client.implementedRequests().sortedForImplemented()
            }
        }.onSuccess { tickets ->
            mutableState.update {
                when (section) {
                    FeedbackSection.REQUESTS -> it.copy(requests = tickets, isLoading = false, error = null)
                    FeedbackSection.MINE -> it.copy(mine = tickets, isLoading = false, error = null)
                    FeedbackSection.IMPLEMENTED -> it.copy(implemented = tickets, isLoading = false, error = null)
                }
            }
        }.onFailure { failure -> mutableState.update { it.copy(isLoading = false, error = failure.displayMessage()) } }
    }

    suspend fun create(title: String, description: String, email: String?, idempotencyKey: String): Boolean = act {
        val ticket = client.createTicket(title, description, email, idempotencyKey)
        mutableState.update { it.copy(mine = listOf(ticket) + it.mine, section = FeedbackSection.MINE) }
    }

    suspend fun open(ticket: Ticket) {
        open(ticket.id, ticket)
    }

    suspend fun open(ticketId: String, initialTicket: Ticket? = null) {
        val requestId = ticketId
        detailRequestId = requestId
        val ticket = initialTicket ?: state.value.allTickets().firstOrNull { it.id == ticketId }
        if (ticket != null) {
            mutableState.update { it.copy(selectedTicket = ticket, comments = emptyList(), isLoading = true) }
        } else {
            mutableState.update { it.copy(comments = emptyList(), isLoading = true) }
        }
        runCatching {
            coroutineScope {
                val refreshed = async { client.ticket(requestId) }
                val comments = async { client.comments(requestId) }
                refreshed.await() to comments.await()
            }
        }.onSuccess { (refreshed, comments) ->
            if (detailRequestId != requestId) return@onSuccess
            replace(refreshed)
            mutableState.update { it.copy(selectedTicket = refreshed, comments = comments, isLoading = false, error = null) }
        }.onFailure { failure ->
            mutableState.update {
                if (detailRequestId == requestId) {
                    it.copy(isLoading = false, error = failure.displayMessage())
                } else it
            }
        }
    }

    fun closeTicket() {
        detailRequestId = null
        mutableState.update { it.copy(selectedTicket = null, comments = emptyList(), isLoading = false, error = null) }
    }

    suspend fun toggleVote(ticket: Ticket) {
        if (!ticket.canVote || ticket.id in state.value.votingTicketIds) return
        mutableState.update { it.copy(votingTicketIds = it.votingTicketIds + ticket.id) }
        runCatching { client.setVote(ticket.id, ticket.hasVoted != true) }
            .onSuccess { updated -> replace(updated); mutableState.update { it.copy(error = null) } }
            .onFailure { failure -> mutableState.update { it.copy(error = failure.displayMessage()) } }
        mutableState.update { it.copy(votingTicketIds = it.votingTicketIds - ticket.id) }
    }

    suspend fun reply(body: String, idempotencyKey: String): Boolean = act {
        val ticket = requireNotNull(state.value.selectedTicket)
        val comment = client.createComment(ticket.id, body, idempotencyKey)
        mutableState.update { it.copy(comments = it.comments + comment) }
    }

    suspend fun edit(comment: Comment, body: String): Boolean = act {
        val ticket = requireNotNull(state.value.selectedTicket)
        val updated = client.updateComment(ticket.id, comment.id, body)
        mutableState.update { state -> state.copy(comments = state.comments.map { if (it.id == updated.id) updated else it }) }
    }

    private suspend fun act(block: suspend () -> Unit): Boolean {
        if (state.value.isActing) return false
        mutableState.update { it.copy(isActing = true) }
        return runCatching { block() }
            .onSuccess { mutableState.update { it.copy(isActing = false, error = null) } }
            .onFailure { failure -> mutableState.update { it.copy(isActing = false, error = failure.displayMessage()) } }
            .isSuccess
    }

    private fun replace(ticket: Ticket) = mutableState.update { state ->
        state.copy(
            requests = state.requests.replace(ticket).sortedForRequests(),
            mine = state.mine.replace(ticket),
            implemented = state.implemented.replace(ticket).sortedForImplemented(),
            selectedTicket = state.selectedTicket?.let { if (it.id == ticket.id) ticket else it },
        )
    }
}

private fun FeedbackUiState.allTickets(): List<Ticket> = requests + mine + implemented

private fun List<Ticket>.replace(ticket: Ticket) = map { if (it.id == ticket.id) ticket else it }
private fun List<Ticket>.sortedForRequests() = sortedWith(
    compareByDescending<Ticket> { it.voteCount ?: 0 }
        .thenByDescending { when (it.status) { TicketStatus.IN_PROGRESS -> 3; TicketStatus.PLANNED -> 2; TicketStatus.IN_REVIEW -> 1; else -> 0 } }
        .thenByDescending(Ticket::createdAt)
        .thenByDescending(Ticket::id),
)

private fun List<Ticket>.sortedForImplemented() = sortedWith(
    compareByDescending<Ticket> { it.implementedAt ?: it.updatedAt }
        .thenByDescending(Ticket::id),
)

internal val Ticket.canVote: Boolean
    get() = voteCount != null && status in setOf(TicketStatus.IN_REVIEW, TicketStatus.PLANNED, TicketStatus.IN_PROGRESS)

private fun Throwable.displayMessage(): String = message?.takeIf(String::isNotBlank) ?: "Something went wrong. Please try again."
