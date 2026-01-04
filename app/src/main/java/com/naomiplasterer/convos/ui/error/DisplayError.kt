package com.naomiplasterer.convos.ui.error

import java.util.UUID

interface DisplayError {
    val title: String
    val description: String
}

data class IdentifiableError(
    val id: String = UUID.randomUUID().toString(),
    val error: DisplayError
) {
    val title: String get() = error.title
    val description: String get() = error.description
}

data class GenericDisplayError(
    override val title: String,
    override val description: String
) : DisplayError

sealed class InviteError : DisplayError {
    data object InvalidFormat : InviteError() {
        override val title = "Invalid Invite"
        override val description = "The invite code format is invalid. Please check and try again."
    }

    data object Expired : InviteError() {
        override val title = "Invite Expired"
        override val description = "This invite has expired and can no longer be used."
    }

    data object ConversationExpired : InviteError() {
        override val title = "Conversation Expired"
        override val description = "This conversation has expired and is no longer available."
    }

    data object SignatureInvalid : InviteError() {
        override val title = "Invalid Signature"
        override val description = "The invite signature could not be verified. This invite may have been tampered with."
    }

    data object NetworkError : InviteError() {
        override val title = "Network Error"
        override val description = "Unable to connect to the network. Please check your connection and try again."
    }

    data class UnknownError(val message: String?) : InviteError() {
        override val title = "Failed Joining"
        override val description = message ?: "An unknown error occurred. Please try again."
    }
}

sealed class ConversationCreationError : DisplayError {
    data object NoClient : ConversationCreationError() {
        override val title = "No Client"
        override val description = "Unable to create a client. Please try again."
    }

    data object CreationFailed : ConversationCreationError() {
        override val title = "Creation Failed"
        override val description = "Failed to create the conversation. Please try again."
    }

    data class UnknownError(val message: String?) : ConversationCreationError() {
        override val title = "Failed Creating"
        override val description = message ?: "An unknown error occurred. Please try again."
    }
}
