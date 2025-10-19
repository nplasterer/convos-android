package com.naomiplasterer.convos.domain.model

data class Member(
    val inboxId: String,
    val addresses: List<String>,
    val permissionLevel: PermissionLevel,
    val consentState: ConsentState,
    val addedAt: Long
)

enum class PermissionLevel {
    MEMBER,
    ADMIN,
    SUPER_ADMIN
}
