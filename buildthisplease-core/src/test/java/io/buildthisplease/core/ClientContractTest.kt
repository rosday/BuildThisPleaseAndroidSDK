package io.buildthisplease.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientContractTest {
    @Test
    fun `ticket payload carries the submission app version`() {
        val ticket = buildThisPleaseJson.encodeToString(TicketInput("Title", "Description", appVersion = "2.4.1"))
        assertTrue(ticket.contains("\"appVersion\":\"2.4.1\""))
    }

    @Test
    fun `optional values are omitted from registration and ticket payloads`() {
        val registration = buildThisPleaseJson.encodeToString(
            RegistrationChallengeInput(
                packageName = "io.buildthisplease.contracttest",
                certificateSha256 = "certificate",
                environment = "production",
                keyId = "p256_test",
                publicKeySpki = "public-key",
                appVersion = "1.0",
                osVersion = "Android",
                subscriptionStatus = SubscriptionStatus.UNKNOWN,
            ),
        )
        assertFalse(registration.contains("revenueCatAppUserId"))
        assertFalse(registration.contains("userEmail"))

        val ticket = buildThisPleaseJson.encodeToString(TicketInput("Title", "Description"))
        assertEquals("{\"title\":\"Title\",\"description\":\"Description\"}", ticket)
    }
}
