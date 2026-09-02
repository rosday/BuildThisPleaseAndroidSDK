package io.buildthisplease.compose

import io.buildthisplease.core.Ticket
import io.buildthisplease.core.TicketStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityRulesTest {
    @Test
    fun `request email validation matches the Worker`() {
        assertTrue(isValidRequestEmail(""))
        assertTrue(isValidRequestEmail("support@example.com"))
        assertFalse(isValidRequestEmail("a@b"))
        assertFalse(isValidRequestEmail("two..dots@example.com"))
        assertFalse(isValidRequestEmail("a".repeat(310) + "@example.com"))
    }

    @Test
    fun `votes are available only for public workflow statuses`() {
        assertTrue(ticket(TicketStatus.IN_REVIEW).canVote)
        assertTrue(ticket(TicketStatus.PLANNED).canVote)
        assertTrue(ticket(TicketStatus.IN_PROGRESS).canVote)
        assertFalse(ticket(TicketStatus.PENDING).canVote)
        assertFalse(ticket(TicketStatus.REJECTED).canVote)
        assertFalse(ticket(TicketStatus.IMPLEMENTED).canVote)
    }

    private fun ticket(status: TicketStatus) = Ticket(
        id = status.name,
        title = status.name,
        description = "Description",
        status = status,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        voteCount = 1,
    )
}
