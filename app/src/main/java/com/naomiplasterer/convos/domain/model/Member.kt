package com.naomiplasterer.convos.domain.model

data class Member(
    val inboxId: String,
    val addresses: List<String>,
    val permissionLevel: PermissionLevel,
    val consentState: ConsentState,
    val addedAt: Long,
    val name: String? = null,
    val imageUrl: String? = null,
    val isCurrentUser: Boolean = false
) {
    val displayName: String
        get() = name ?: "Somebody"

    val isAdmin: Boolean
        get() = permissionLevel == PermissionLevel.ADMIN || permissionLevel == PermissionLevel.SUPER_ADMIN
}

enum class PermissionLevel {
    MEMBER,
    ADMIN,
    SUPER_ADMIN
}
