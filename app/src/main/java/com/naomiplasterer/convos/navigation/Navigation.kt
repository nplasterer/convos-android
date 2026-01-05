package com.naomiplasterer.convos.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.naomiplasterer.convos.ui.conversation.ConversationScreen
import com.naomiplasterer.convos.ui.conversations.ConversationsScreen
import com.naomiplasterer.convos.ui.newconversation.NewConversationMode
import com.naomiplasterer.convos.ui.newconversation.NewConversationScreen
import com.naomiplasterer.convos.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Conversations : Screen("conversations")
    object ConversationDetail : Screen("conversation/{conversationId}") {
        fun createRoute(conversationId: String) = "conversation/$conversationId"
    }
    object NewConversation : Screen("new_conversation")
    object Settings : Screen("settings")
}

@Composable
fun ConvosNavigation(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Conversations.route
    ) {
        composable(Screen.Conversations.route) {
            ConversationsScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(Screen.ConversationDetail.createRoute(conversationId))
                },
                onNewConversationClick = {
                    // Navigate to create mode for starting a new conversation
                    navController.navigate("${Screen.NewConversation.route}?mode=create") {
                        launchSingleTop = true
                    }
                },
                onScanConversationClick = {
                    navController.navigate("${Screen.NewConversation.route}?mode=scan")
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.ConversationDetail.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "convos://conversation/{conversationId}" },
                navDeepLink { uriPattern = "https://convos.app/conversation/{conversationId}" }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            ConversationScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = "${Screen.NewConversation.route}?mode={mode}&inviteCode={inviteCode}",
            arguments = listOf(
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = "scan"
                },
                navArgument("inviteCode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "convos://i/{inviteCode}" },
                navDeepLink { uriPattern = "https://convos.app/i/{inviteCode}" }
            )
        ) { backStackEntry ->
            val modeString = backStackEntry.arguments?.getString("mode") ?: "scan"
            val inviteCode = backStackEntry.arguments?.getString("inviteCode")

            val mode = when (modeString) {
                "create" -> NewConversationMode.CREATE
                "scan" -> NewConversationMode.SCAN
                "manual" -> NewConversationMode.MANUAL
                else -> NewConversationMode.SCAN
            }

            NewConversationScreen(
                onBackClick = { navController.popBackStack() },
                onConversationJoined = { conversationId ->
                    navController.navigate(Screen.ConversationDetail.createRoute(conversationId)) {
                        popUpTo(Screen.Conversations.route) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                },
                initialMode = mode,
                initialInviteCode = inviteCode
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

