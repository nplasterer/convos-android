package com.naomiplasterer.convos.data.mapper

import com.naomiplasterer.convos.data.local.entity.ConversationEntity
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import com.naomiplasterer.convos.domain.model.ConversationKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMapperTest {

    @Test
    fun `toDomain converts entity to domain with ALLOWED consent and GROUP kind`() {
        val entity = ConversationEntity(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = "invite-tag-uuid",
            consent = "allowed",
            kind = "group",
            name = "Test Group",
            description = "A test group",
            imageUrl = "https://example.com/image.png",
            isPinned = true,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = 1234567900L,
            expiresAt = null
        )

        val domain = entity.toDomain()

        assertEquals("conv-123", domain.id)
        assertEquals("inbox-456", domain.inboxId)
        assertEquals("client-789", domain.clientId)
        assertEquals("/xmtp/0/topic", domain.topic)
        assertEquals("creator-inbox-id", domain.creatorInboxId)
        assertEquals("invite-tag-uuid", domain.inviteTag)
        assertEquals(ConsentState.ALLOWED, domain.consent)
        assertEquals(ConversationKind.GROUP, domain.kind)
        assertEquals("Test Group", domain.name)
        assertEquals("A test group", domain.description)
        assertEquals("https://example.com/image.png", domain.imageUrl)
        assertEquals(true, domain.isPinned)
        assertEquals(false, domain.isUnread)
        assertEquals(false, domain.isMuted)
        assertEquals(false, domain.isDraft)
        assertEquals(1234567890L, domain.createdAt)
        assertEquals(1234567900L, domain.lastMessageAt)
        assertEquals(null, domain.expiresAt)
    }

    @Test
    fun `toDomain converts entity with DENIED consent and DM kind`() {
        val entity = ConversationEntity(
            id = "dm-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/dm-topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = "denied",
            kind = "dm",
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = true,
            isMuted = true,
            isDraft = true,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = 1234567999L
        )

        val domain = entity.toDomain()

        assertEquals(ConsentState.DENIED, domain.consent)
        assertEquals(ConversationKind.DM, domain.kind)
        assertEquals(null, domain.name)
        assertEquals(null, domain.inviteTag)
        assertEquals(true, domain.isUnread)
        assertEquals(true, domain.isMuted)
        assertEquals(true, domain.isDraft)
        assertEquals(1234567999L, domain.expiresAt)
    }

    @Test
    fun `toDomain handles unknown consent state`() {
        val entity = ConversationEntity(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = "unknown",
            kind = "group",
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = null
        )

        val domain = entity.toDomain()
        assertEquals(ConsentState.UNKNOWN, domain.consent)
    }

    @Test
    fun `toDomain defaults to UNKNOWN for invalid consent value`() {
        val entity = ConversationEntity(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = "invalid_consent",
            kind = "group",
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = null
        )

        val domain = entity.toDomain()
        assertEquals(ConsentState.UNKNOWN, domain.consent)
    }

    @Test
    fun `toDomain defaults to GROUP for invalid kind value`() {
        val entity = ConversationEntity(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = "allowed",
            kind = "invalid_kind",
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = null
        )

        val domain = entity.toDomain()
        assertEquals(ConversationKind.GROUP, domain.kind)
    }

    @Test
    fun `toEntity converts domain to entity with ALLOWED consent and GROUP kind`() {
        val domain = Conversation(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = "invite-tag-uuid",
            consent = ConsentState.ALLOWED,
            kind = ConversationKind.GROUP,
            name = "Test Group",
            description = "A test group",
            imageUrl = "https://example.com/image.png",
            isPinned = true,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = 1234567900L,
            expiresAt = null
        )

        val entity = domain.toEntity()

        assertEquals("conv-123", entity.id)
        assertEquals("inbox-456", entity.inboxId)
        assertEquals("client-789", entity.clientId)
        assertEquals("/xmtp/0/topic", entity.topic)
        assertEquals("creator-inbox-id", entity.creatorInboxId)
        assertEquals("invite-tag-uuid", entity.inviteTag)
        assertEquals("allowed", entity.consent)
        assertEquals("group", entity.kind)
        assertEquals("Test Group", entity.name)
        assertEquals("A test group", entity.description)
        assertEquals("https://example.com/image.png", entity.imageUrl)
        assertEquals(true, entity.isPinned)
        assertEquals(false, entity.isUnread)
        assertEquals(false, entity.isMuted)
        assertEquals(false, entity.isDraft)
        assertEquals(1234567890L, entity.createdAt)
        assertEquals(1234567900L, entity.lastMessageAt)
        assertEquals(null, entity.expiresAt)
    }

    @Test
    fun `toEntity converts domain with DENIED consent and DM kind`() {
        val domain = Conversation(
            id = "dm-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/dm-topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = ConsentState.DENIED,
            kind = ConversationKind.DM,
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = true,
            isMuted = true,
            isDraft = true,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = 1234567999L
        )

        val entity = domain.toEntity()

        assertEquals("denied", entity.consent)
        assertEquals("dm", entity.kind)
        assertEquals(null, entity.name)
        assertEquals(null, entity.inviteTag)
        assertEquals(true, entity.isUnread)
        assertEquals(true, entity.isMuted)
        assertEquals(true, entity.isDraft)
        assertEquals(1234567999L, entity.expiresAt)
    }

    @Test
    fun `toEntity converts UNKNOWN consent state`() {
        val domain = Conversation(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = ConsentState.UNKNOWN,
            kind = ConversationKind.GROUP,
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = null
        )

        val entity = domain.toEntity()
        assertEquals("unknown", entity.consent)
    }

    @Test
    fun `roundtrip conversion preserves data`() {
        val originalEntity = ConversationEntity(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = "invite-tag-uuid",
            consent = "allowed",
            kind = "group",
            name = "Test Group",
            description = "A test group",
            imageUrl = "https://example.com/image.png",
            isPinned = true,
            isUnread = false,
            isMuted = true,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = 1234567900L,
            expiresAt = 1234567999L
        )

        val domain = originalEntity.toDomain()
        val roundtripEntity = domain.toEntity()

        assertEquals(originalEntity, roundtripEntity)
    }

    @Test
    fun `roundtrip conversion with null values preserves data`() {
        val originalEntity = ConversationEntity(
            id = "conv-123",
            inboxId = "inbox-456",
            clientId = "client-789",
            topic = "/xmtp/0/topic",
            creatorInboxId = "creator-inbox-id",
            inviteTag = null,
            consent = "denied",
            kind = "dm",
            name = null,
            description = null,
            imageUrl = null,
            isPinned = false,
            isUnread = false,
            isMuted = false,
            isDraft = false,
            createdAt = 1234567890L,
            lastMessageAt = null,
            expiresAt = null
        )

        val domain = originalEntity.toDomain()
        val roundtripEntity = domain.toEntity()

        assertEquals(originalEntity, roundtripEntity)
    }
}
