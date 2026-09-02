package io.buildthisplease.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `older server response defaults comment ownership to false`() {
        val comment = json.decodeFromString<Comment>(
            """{"id":"c1","ticketId":"t1","author":"user","body":"Hi","isEdited":false,"isHidden":false,"createdAt":"now","updatedAt":"now"}""",
        )
        assertFalse(comment.isMine)
    }

    @Test
    fun `server can identify the current installation comment`() {
        val comment = json.decodeFromString<Comment>(
            """{"id":"c1","ticketId":"t1","author":"user","body":"Hi","isEdited":false,"isHidden":false,"isMine":true,"createdAt":"now","updatedAt":"now"}""",
        )
        assertTrue(comment.isMine)
    }
}
