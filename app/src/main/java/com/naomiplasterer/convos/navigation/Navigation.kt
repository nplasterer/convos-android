package com.naomiplasterer.convos.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.naomiplasterer.convos.ui.conversations.ConversationsScreen
import com.naomiplasterer.convos.ui.conversation.ConversationScreen
import com.naomiplasterer.convos.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Conversations : Screen("conversations")
    object ConversationDetail : Screen("conversation/{conversationId}") {
        fun createRoute(conversationId: String) = "conversation/$conversationId"
    }
    object NewConversation : Screen("new_conversation")
    object Settings : Screen("settings")
    object Profile : Screen("profile/{conversationId}") {
        fun createRoute(conversationId: String) = "profile/$conversationId"
    }
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
                    navController.navigate(Screen.NewConversation.route)
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
        ) {
            ConversationScreen(
                onBackClick = { navController.popBackStack() },
                onInfoClick = { }
            )
        }

        composable(
            route = Screen.NewConversation.route,
            deepLinks = listOf(
                navDeepLink { uriPattern = "convos://i/{inviteCode}" },
                navDeepLink { uriPattern = "https://convos.app/i/{inviteCode}" }
            )
        ) {
            NewConversationPlaceholder(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun ConversationDetailPlaceholder(
    conversationId: String,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Conversation Detail")
            Text("ID: $conversationId")
            Button(onClick = onBackClick) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun NewConversationPlaceholder(
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("New Conversation")
            Button(onClick = onBackClick) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun SettingsPlaceholder(
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings")
            Button(onClick = onBackClick) {
                Text("Back")
            }
        }
    }
}
