package dev.nucleusframework.nucleus.systeminfo.model

data class UserInfo(
    val name: String,
    val id: String,
    val groupId: String,
    val groups: List<String>,
)
