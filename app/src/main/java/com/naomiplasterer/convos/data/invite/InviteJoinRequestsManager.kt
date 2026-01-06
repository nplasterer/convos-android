package com.naomiplasterer.convos.data.invite

import com.naomiplasterer.convos.crypto.Base64URL
import com.naomiplasterer.convos.util.InviteConversationToken
import com.naomiplasterer.convos.crypto.JoinRequestResult
import com.naomiplasterer.convos.crypto.SignedInviteValidator
import com.naomiplasterer.convos.crypto.getCreatorInboxIdString
import com.naomiplasterer.convos.proto.InviteProtos
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.xmtp.android.library.Client
import org.xmtp.android.library.Dm
import org.xmtp.android.library.ConsentState
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InviteJoinRequestsManager"

@Singleton
class InviteJoinRequestsManager @Inject constructor(
    private val xmtpClientManager: XMTPClientManager
) {

    /**
     * Store pending invites that we're waiting to be added to.
     * Maps invite tag to SignedInvite.
     */
    private val pendingInvites = mutableMapOf<String, InviteProtos.SignedInvite>()

    /**
     * Flow that emits conversation IDs when a group is successfully matched to a pending invite.
     * Using replay=1 to ensure late subscribers get the last emitted value.
     */
    private val _groupMatched = MutableSharedFlow<String>(replay = 1)
    val groupMatched: SharedFlow<String> = _groupMatched.asSharedFlow()

    /**
     * Store a pending invite after sending a join request.
     * This will be used to match against incoming groups.
     */
    fun storePendingInvite(signedInvite: InviteProtos.SignedInvite) {
        val payload = SignedInviteValidator.getPayload(signedInvite)
        val tag = payload.tag
        pendingInvites[tag] = signedInvite
    }

    /**
     * Wait reactively for a conversation matching the pending invite tag to appear in the database.
     * This matches iOS's ValueObservation approach - event-driven instead of polling.
     *
     * @param tag The invite tag to watch for
     * @param conversationDao The DAO to observe
     * @return Flow that emits the conversation ID when found (null until then)
     */
    fun waitForJoinedConversation(
        tag: String,
        conversationDao: com.naomiplasterer.convos.data.local.dao.ConversationDao
    ): kotlinx.coroutines.flow.Flow<String?> {
        Log.d(TAG, "Starting reactive observation for invite tag: $tag")
        return conversationDao.observeConversationByTag(tag)
    }

    /**
     * Check if a group tag matches any pending invite (read-only, doesn't mutate state).
     * Used by syncConversations to check consent without side effects.
     */
    suspend fun hasPendingInvite(groupId: String, client: Client): Boolean = withContext(Dispatchers.IO) {
        try {
            val group = client.conversations.listGroups().find { it.id == groupId }
            if (group == null) {
                Log.w(TAG, "Group $groupId not found for pending invite check")
                return@withContext false
            }

            // Extract tag from group metadata
            val groupTag = try {
                val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                val metadata =
                    com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                        conversationGroup
                    )
                if (metadata != null && !metadata.tag.isBlank()) {
                    metadata.tag
                } else {
                    // Fallback to legacy hex format
                    val groupDescription = group.description()
                    if (groupDescription.isNotEmpty()) {
                        val legacyMetadata =
                            com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.parseLegacyHexMetadata(
                                groupDescription
                            )
                        if (legacyMetadata != null && !legacyMetadata.tag.isBlank()) {
                            legacyMetadata.tag
                        } else {
                            return@withContext false
                        }
                    } else {
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract tag from group $groupId", e)
                return@withContext false
            }

            // Check if tag matches any pending invite (read-only)
            val hasMatch = pendingInvites.containsKey(groupTag)
            if (hasMatch) {
                Log.d(TAG, "✅ Group $groupId has pending invite with tag: $groupTag")
            }
            hasMatch
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pending invite for group", e)
            false
        }
    }

    /**
     * Check if a new group matches any pending invites by comparing tags.
     * If it matches, update consent to ALLOWED and remove from pending.
     * If not, update consent to DENIED.
     */
    suspend fun checkGroupForPendingInvite(
        groupId: String,
        client: Client
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val group = client.conversations.listGroups().find { it.id == groupId }
            if (group == null) {
                Log.w(TAG, "Group $groupId not found")
                return@withContext false
            }

            // First, try to get the tag from the group's custom metadata
            val groupTag = try {
                // Try to get from appData (iOS compatible format)
                val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                val metadata =
                    com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                        conversationGroup
                    )
                if (metadata != null && !metadata.tag.isBlank()) {
                    metadata.tag
                } else {
                    // Fallback to legacy hex format in description if appData is empty
                    val groupDescription = group.description()
                    if (groupDescription.isNotEmpty()) {
                        val legacyMetadata =
                            com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.parseLegacyHexMetadata(
                                groupDescription
                            )
                        if (legacyMetadata != null && !legacyMetadata.tag.isBlank()) {
                            legacyMetadata.tag
                        } else {
                            throw IllegalStateException("Tag is empty or blank")
                        }
                    } else {
                        throw IllegalStateException("No metadata found in group")
                    }
                }
            } catch (e: Exception) {
                // If we can't parse the metadata or tag is empty, check if we have exactly ONE pending invite from this creator.

                // Get the group's creator (the member who added us)
                val members = group.members()
                val creatorInboxId = members.firstOrNull { member ->
                    try {
                        // The creator should be in the pending invites
                        pendingInvites.values.any { invite ->
                            val payload = SignedInviteValidator.getPayload(invite)
                            // Convert bytes to hex string for comparison
                            val creatorInboxIdStr = payload.getCreatorInboxIdString()
                            creatorInboxIdStr == member.inboxId
                        }
                    } catch (ex: Exception) {
                        false
                    }
                }?.inboxId

                if (creatorInboxId != null) {
                    // Find the pending invite from this creator
                    val matchingEntry = pendingInvites.entries.find { (_, invite) ->
                        val payload = SignedInviteValidator.getPayload(invite)
                        val creatorInboxIdStr = payload.getCreatorInboxIdString()
                        creatorInboxIdStr == creatorInboxId
                    }

                    if (matchingEntry != null) {
                        matchingEntry.key
                    } else {
                        group.updateConsentState(ConsentState.DENIED)
                        return@withContext false
                    }
                } else {
                    group.updateConsentState(ConsentState.DENIED)
                    return@withContext false
                }
            }

            if (pendingInvites.containsKey(groupTag)) {
                Log.d(TAG, "✅ Group $groupId matched pending invite with tag: $groupTag")
                group.updateConsentState(ConsentState.ALLOWED)
                pendingInvites.remove(groupTag)
                Log.d(TAG, "📢 Emitting groupMatched for conversation: $groupId")
                _groupMatched.emit(groupId)
                true
            } else {
                Log.d(TAG, "❌ Group $groupId tag '$groupTag' does not match any pending invite")
                Log.d(TAG, "Pending invite tags: ${pendingInvites.keys.joinToString()}")
                group.updateConsentState(ConsentState.DENIED)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking group for pending invite", e)
            false
        }
    }

    suspend fun processJoinRequests(
        client: Client,
        sinceNs: Long? = null
    ): List<JoinRequestResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<JoinRequestResult>()

        try {
            client.conversations.sync()

            val allDms = client.conversations.listDms()
            val dms = allDms.filter { dm ->
                try {
                    // Check all DMs regardless of consent state, since consent may be synced
                    // across installations and the joiner might have set it to DENIED
                    sinceNs == null || dm.createdAtNs >= sinceNs
                } catch (e: Exception) {
                    false
                }
            }

            coroutineScope {
                val deferredResults = dms.map { dm ->
                    async {
                        try {
                            processMessagesForDm(dm, client)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing messages as join requests", e)
                            null
                        }
                    }
                }

                deferredResults.awaitAll().filterNotNull().forEach { result ->
                    results.add(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing DMs", e)
        }

        results
    }

    private suspend fun processMessagesForDm(dm: Dm, client: Client): JoinRequestResult? {
        return withContext(Dispatchers.IO) {
            dm.sync()

            val messages = dm.messages().filter { message ->
                message.senderInboxId != client.inboxId
            }

            for (message in messages) {
                val text = try {
                    message.body
                } catch (e: Exception) {
                    continue
                }

                val signedInvite = try {
                    SignedInviteValidator.parseFromURLSafeSlug(text)
                } catch (e: Exception) {
                    continue
                }

                if (SignedInviteValidator.hasExpired(signedInvite)) {
                    continue
                }

                if (SignedInviteValidator.conversationHasExpired(signedInvite)) {
                    continue
                }

                val payload = SignedInviteValidator.getPayload(signedInvite)
                val creatorInboxIdStr = payload.getCreatorInboxIdString()
                if (creatorInboxIdStr != client.inboxId) {
                    Log.e(
                        TAG,
                        "Received join request for invite not created by this inbox - blocking DM"
                    )
                    try {
                        dm.updateConsentState(ConsentState.DENIED)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to block DM", e)
                    }
                    continue
                }

                val verifiedSignature = try {
                    SignedInviteValidator.verify(signedInvite, client)
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during signature verification - blocking DM", e)
                    try {
                        dm.updateConsentState(ConsentState.DENIED)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to block DM", ex)
                    }
                    continue
                }

                if (!verifiedSignature) {
                    Log.e(TAG, "Signature verification failed - blocking DM")
                    try {
                        dm.updateConsentState(ConsentState.DENIED)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to block DM", e)
                    }
                    continue
                }

                // Get the actual wallet private key to decrypt the conversation token
                // The joiner encrypted it with our wallet's secp256k1 private key
                val privateKey = xmtpClientManager.getPrivateKeyData(client.inboxId)
                if (privateKey == null) {
                    Log.e(TAG, "Failed to get private key for inbox: ${client.inboxId}")
                    continue
                }

                val conversationTokenBytes = payload.conversationToken.toByteArray()
                val conversationTokenStr = Base64URL.encode(conversationTokenBytes)
                val decodeResult = InviteConversationToken.decodeConversationToken(
                    conversationTokenStr,
                    client.inboxId,
                    privateKey
                )
                val conversationId = decodeResult.getOrNull()
                if (conversationId == null) {
                    Log.e(TAG, "Failed to decode conversation token")
                    continue
                }

                val group = try {
                    client.conversations.listGroups().find { it.id == conversationId }
                } catch (e: Exception) {
                    Log.w(TAG, "Conversation $conversationId not found")
                    continue
                }

                if (group == null) {
                    Log.w(TAG, "Conversation $conversationId not found")
                    continue
                }

                val consentState = try {
                    group.consentState()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get consent state for group")
                    continue
                }

                if (consentState != ConsentState.ALLOWED) {
                    Log.w(
                        TAG,
                        "Conversation $conversationId consent state is $consentState, not allowed"
                    )
                    continue
                }

                val senderInboxId = message.senderInboxId
                Log.d(TAG, "Adding $senderInboxId to group ${group.id}")

                try {
                    group.addMembers(listOf(senderInboxId))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add member to group", e)
                    continue
                }

                val conversationName = try {
                    group.name()
                } catch (e: Exception) {
                    null
                }

                Log.d(TAG, "Successfully added $senderInboxId to conversation $conversationId")

                try {
                    dm.updateConsentState(ConsentState.ALLOWED)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update DM consent state", e)
                }

                return@withContext JoinRequestResult(
                    conversationId = group.id,
                    conversationName = conversationName
                )
            }

            null
        }
    }

}
