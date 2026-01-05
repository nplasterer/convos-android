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
        Log.d(TAG, "Stored pending invite with tag: $tag")
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
                // Convert the Group to Conversation.Group
                val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                val metadata =
                    com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                        conversationGroup
                    )
                if (metadata != null && !metadata.tag.isBlank()) {
                    Log.d(TAG, "Extracted tag from group appData: ${metadata.tag}")
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
                            Log.d(
                                TAG,
                                "Extracted tag from legacy hex-encoded description: ${legacyMetadata.tag}"
                            )
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
                // This is a workaround for when the group creator doesn't set ConversationCustomMetadata or sets an empty tag.
                Log.d(
                    TAG,
                    "No custom metadata or empty tag in group description, checking by creator inbox ID"
                )

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
                        Log.d(
                            TAG,
                            "Group $groupId matched pending invite by creator inbox ID, tag: ${matchingEntry.key}"
                        )
                        matchingEntry.key
                    } else {
                        Log.d(
                            TAG,
                            "Group $groupId from creator $creatorInboxId but no pending invite found - denying"
                        )
                        group.updateConsentState(ConsentState.DENIED)
                        return@withContext false
                    }
                } else {
                    Log.d(
                        TAG,
                        "Group $groupId has no matching creator in pending invites - denying"
                    )
                    group.updateConsentState(ConsentState.DENIED)
                    return@withContext false
                }
            }

            if (pendingInvites.containsKey(groupTag)) {
                Log.d(TAG, "Group $groupId matches pending invite with tag $groupTag - approving")
                group.updateConsentState(ConsentState.ALLOWED)
                pendingInvites.remove(groupTag)

                // Emit the group ID so listeners know a group was successfully joined
                Log.d(TAG, "Emitting groupMatched event for conversation: $groupId")
                _groupMatched.emit(groupId)
                Log.d(
                    TAG,
                    "Successfully emitted groupMatched event, current replay cache: ${_groupMatched.replayCache}"
                )

                true
            } else {
                Log.d(
                    TAG,
                    "Group $groupId with tag $groupTag does not match any pending invites - denying"
                )
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
            Log.d(TAG, "Listing all DMs for join requests")

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

            Log.d(TAG, "Found ${dms.size} DMs to check for join requests")

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

            Log.d(TAG, "Completed DM sync for join requests, found ${results.size} valid requests")
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

            Log.d(TAG, "Found ${messages.size} messages as possible join requests")

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
                    Log.d(TAG, "Invite expired, skipping")
                    continue
                }

                if (SignedInviteValidator.conversationHasExpired(signedInvite)) {
                    Log.d(TAG, "Conversation expired, skipping")
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
