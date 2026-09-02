package io.buildthisplease.compose

import androidx.lifecycle.SavedStateHandle
import io.buildthisplease.core.BuildThisPleaseClientProtocol
import io.buildthisplease.core.Comment
import io.buildthisplease.core.ProjectConfiguration
import io.buildthisplease.core.SubscriptionStatus
import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackControllerTest {
    @Test
    fun `refresh result remains attached to the section that initiated it`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        controller.load()
        controller.selectSection(FeedbackSection.REQUESTS)
        val gate = CompletableDeferred<List<Ticket>>()
        client.nextRequests = gate

        val refresh = async { controller.refreshCurrentSection() }
        runCurrent()
        controller.selectSection(FeedbackSection.MINE)
        gate.complete(listOf(fixtureTicket("new-request")))
        refresh.await()

        assertEquals(listOf("new-request"), controller.state.value.requests.map(Ticket::id))
        assertEquals(listOf("mine"), controller.state.value.mine.map(Ticket::id))
    }

    @Test
    fun `closing a ticket prevents an in-flight detail request from reopening it`() = runTest {
        val client = ControllableClient()
        val controller = FeedbackController(client)
        val gate = CompletableDeferred<Ticket>()
        client.nextTicket = gate

        val request = async { controller.open(fixtureTicket("request")) }
        runCurrent()
        controller.closeTicket()
        gate.complete(fixtureTicket("request", title = "Refreshed"))
        request.await()

        assertNull(controller.state.value.selectedTicket)
    }

    @Test
    fun `view model restores section and selected ticket from saved state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val handle = SavedStateHandle(
                mapOf("section" to FeedbackSection.MINE.name, "selectedTicketId" to "mine"),
            )
            val model = FeedbackViewModel(ControllableClient(), handle)
            advanceUntilIdle()

            assertEquals(FeedbackSection.MINE, model.state.value.section)
            assertEquals("mine", model.state.value.selectedTicket?.id)

            model.selectSection(FeedbackSection.IMPLEMENTED)
            advanceUntilIdle()
            assertEquals(FeedbackSection.IMPLEMENTED.name, handle.get<String>("section"))
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class ControllableClient : BuildThisPleaseClientProtocol {
    var nextRequests: CompletableDeferred<List<Ticket>>? = null
    var nextTicket: CompletableDeferred<Ticket>? = null

    override suspend fun configuration() = ProjectConfiguration(
        ProjectConfiguration.Project("mock", "Mock", "mock", "#5B5CE2"),
        ProjectConfiguration.Capabilities(true, true),
    )
    override suspend fun requests(): List<Ticket> = nextRequests?.also { nextRequests = null }?.await() ?: listOf(fixtureTicket("request"))
    override suspend fun myRequests(): List<Ticket> = listOf(fixtureTicket("mine", status = TicketStatus.PENDING))
    override suspend fun implementedRequests(): List<Ticket> = listOf(fixtureTicket("done", status = TicketStatus.IMPLEMENTED))
    override suspend fun ticket(id: String): Ticket = nextTicket?.also { nextTicket = null }?.await() ?: fixtureTicket(id)
    override suspend fun comments(ticketId: String) = emptyList<Comment>()
    override suspend fun createTicket(title: String, description: String, email: String?, idempotencyKey: String) = fixtureTicket("created", title)
    override suspend fun setVote(ticketId: String, voted: Boolean) = fixtureTicket(ticketId).copy(hasVoted = voted)
    override suspend fun createComment(ticketId: String, body: String, idempotencyKey: String): Comment = error("Not used")
    override suspend fun updateComment(ticketId: String, commentId: String, body: String): Comment = error("Not used")
    override suspend fun updateSubscriptionStatus(status: SubscriptionStatus) = Unit
    override suspend fun updateUserIdentity(revenueCatAppUserId: String?, email: String?) = Unit
}

private fun fixtureTicket(
    id: String,
    title: String = id,
    status: TicketStatus = TicketStatus.IN_REVIEW,
) = Ticket(
    id = id,
    title = title,
    description = "Description",
    status = status,
    createdAt = "2026-01-01T00:00:00.000Z",
    updatedAt = "2026-01-01T00:00:00.000Z",
    voteCount = if (status == TicketStatus.IN_REVIEW) 1 else null,
)
