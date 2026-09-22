package com.yunian.ai

sealed class MainRoute(val route: String) {

    object Home : MainRoute("home")
    object Contacts : MainRoute("contacts")
    object Profile : MainRoute("profile")

    data class Chat(val companionId: Long) : MainRoute("chat/$companionId")
    data class ChatDetail(val companionId: Long) : MainRoute("chat_detail/$companionId")

    data class DndSettings(val companionId: Long) : MainRoute("chat_detail_dnd/$companionId")
    data class VoiceCall(val companionId: Long) : MainRoute("voice_call/$companionId")

    data class GroupChat(val groupId: Long) : MainRoute("group_chat/$groupId")
    data class GroupDetail(val groupId: Long) : MainRoute("group_detail/$groupId")
    object CreateGroup : MainRoute("create_group")

    object CreateCompanion : MainRoute("create")
    data class EditCompanion(val companionId: Long) : MainRoute("edit/$companionId")

    object Settings : MainRoute("settings")
    object TtsSettings : MainRoute("tts_settings")
    object TokenUsage : MainRoute("token_usage")
    object Theme : MainRoute("theme")
    object Language : MainRoute("language")
    object BackgroundSettings : MainRoute("background_settings")
    object CheckUpdate : MainRoute("check_update")
    object FrameRate : MainRoute("frame_rate")
    object YandereMode : MainRoute("yandere_mode")
    object ExperimentalFeatures : MainRoute("experimental_features")

    object GeneralSettings : MainRoute("general_settings")
    object SettingsGeneralCategory : MainRoute("settings_general_category")
    object SettingsTools : MainRoute("settings_tools")
    object SettingsPermissions : MainRoute("settings_permissions")
    object SettingsAboutYuNian : MainRoute("settings_about_yunian")

    object RoleManager : MainRoute("role_manager")

    object ProfileSettings : MainRoute("profile_settings")

    object UserProfileReadonly : MainRoute("user_profile_readonly")
    object Memory : MainRoute("memory")
    object About : MainRoute("about")
    object AgreementView : MainRoute("agreement_view")
    object Team : MainRoute("team")
    object Support : MainRoute("support")
    object Thanks : MainRoute("thanks")
    object ThanksFullList : MainRoute("thanks_full_list")
    object OriginOSAdaption : MainRoute("originos_adaption")

    object WeChatSettings : MainRoute("wechat_settings")
    object WeChatBind : MainRoute("wechat_bind")

    object QQBotSettings : MainRoute("qqbot_settings")

    object DataBackup : MainRoute("data_backup")
    object BackupExportSelect : MainRoute("backup_export_select")

    object Coffee : MainRoute("coffee")

    data class CoffeeProduct(val deptId: Long, val productId: Long) : MainRoute("coffee_product/$deptId/$productId")

    object CoffeeSettings : MainRoute("coffee_settings")

    object CoffeeToken : MainRoute("coffee_token")

    object CoffeeOrderQuery : MainRoute("coffee_order")

    data class CoffeeOrderQueryWithId(val orderId: String) : MainRoute("coffee_order/$orderId")

    object Automation : MainRoute("automation")

    object Worldbook : MainRoute("worldbook")
    data class WorldbookDetail(val worldbookId: Long) : MainRoute("worldbook_detail/$worldbookId")
    object Skills : MainRoute("skills")
    object McpSettings : MainRoute("mcp_settings")

    companion object {
        val mainTabRoutes = setOf("home", "contacts", "profile")

        fun isMainTabRoute(route: String?): Boolean = route in mainTabRoutes

        fun fromRoute(route: String?): MainRoute = when {
            route == null -> Home
            route == "home" -> Home
            route == "contacts" -> Contacts
            route == "profile" -> Profile
            route == "create" -> CreateCompanion
            route == "create_group" -> CreateGroup
            route == "settings" -> Settings
            route == "tts_settings" -> TtsSettings
            route == "token_usage" -> TokenUsage
            route == "profile_settings" -> ProfileSettings
            route == "user_profile_readonly" -> UserProfileReadonly
            route == "memory" -> Memory
            route == "role_manager" -> RoleManager
            route == "theme" -> Theme
            route == "language" -> Language
            route == "background_settings" -> BackgroundSettings
            route == "check_update" -> CheckUpdate
            route == "about" -> About
            route == "agreement_view" -> AgreementView
            route == "frame_rate" -> FrameRate
            route == "yandere_mode" -> YandereMode
            route == "experimental_features" -> ExperimentalFeatures
            route == "general_settings" -> GeneralSettings
            route == "settings_general_category" -> SettingsGeneralCategory
            route == "settings_tools" -> SettingsTools
            route == "settings_permissions" -> SettingsPermissions
            route == "settings_about_yunian" -> SettingsAboutYuNian
            route == "team" -> Team
            route == "support" -> Support
            route == "thanks" -> Thanks
            route == "thanks_full_list" -> ThanksFullList
            route == "originos_adaption" -> OriginOSAdaption
            route == "wechat_settings" -> WeChatSettings
            route == "wechat_bind" -> WeChatBind
            route == "qqbot_settings" -> QQBotSettings
            route == "data_backup" -> DataBackup
            route == "backup_export_select" -> BackupExportSelect
            route == "coffee" -> Coffee
            route == "coffee_settings" -> CoffeeSettings
            route == "coffee_token" -> CoffeeToken
            route == "coffee_order" -> CoffeeOrderQuery
            route == "automation" -> Automation
            route == "worldbook" -> Worldbook
            route == "skills" -> Skills
            route == "mcp_settings" -> McpSettings
            route?.startsWith("worldbook_detail/") == true -> WorldbookDetail(route.removePrefix("worldbook_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("coffee_order/") == true -> {
                CoffeeOrderQueryWithId(route.removePrefix("coffee_order/"))
            }
            route?.startsWith("coffee_product/") == true -> {
                val parts = route.removePrefix("coffee_product/").split("/")
                CoffeeProduct(parts.getOrNull(0)?.toLongOrNull() ?: 0L, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            }
            route?.startsWith("chat/") == true -> Chat(route.removePrefix("chat/").toLongOrNull() ?: 0L)
            route?.startsWith("chat_detail_dnd/") == true -> DndSettings(route.removePrefix("chat_detail_dnd/").toLongOrNull() ?: 0L)
            route?.startsWith("chat_detail/") == true -> ChatDetail(route.removePrefix("chat_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("voice_call/") == true -> VoiceCall(route.removePrefix("voice_call/").toLongOrNull() ?: 0L)
            route?.startsWith("group_chat/") == true -> GroupChat(route.removePrefix("group_chat/").toLongOrNull() ?: 0L)
            route?.startsWith("group_detail/") == true -> GroupDetail(route.removePrefix("group_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("edit/") == true -> EditCompanion(route.removePrefix("edit/").toLongOrNull() ?: 0L)
            else -> Home
        }
    }
}
