package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Группа серверов, добавленных ключом, а не подпиской. */
const val STANDALONE_GROUP_ID = AppConfig.STANDALONE_SUBSCRIPTION_ID

/**
 * Ключ раздела избранного для свёрнутых карточек. Группы в хранилище у него нет:
 * закреплённые сервера собираются из всех остальных.
 */
const val PINNED_GROUP_KEY = "__pinned_servers__"

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val statusText: String = "",
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val serviceStartTime: Long? = null
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object CancelTesting : MainAction
    data object UpdateSubscriptions : MainAction

    // Хозяйство группы: действия над одной подпиской или над разделом отдельных серверов
    data class SortGroupByPing(val groupId: String) : MainAction
    data class RemoveDuplicatesInGroup(val groupId: String) : MainAction
    data class RemoveInvalidInGroup(val groupId: String) : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class TogglePinned(val guid: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data class LocateHandled(val target: LocateTarget) : MainAction
    
    data class TestProfilePing(val subscriptionId: String) : MainAction
}
