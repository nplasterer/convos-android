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

}

enum class PermissionLevel {
    MEMBER,
    ADMIN,
    SUPER_ADMIN
}
