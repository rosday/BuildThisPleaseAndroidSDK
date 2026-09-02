package io.buildthisplease.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClientContractTest {
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
