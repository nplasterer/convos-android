package com.naomiplasterer.convos

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import com.naomiplasterer.convos.navigation.ConvosNavigation
import com.naomiplasterer.convos.navigation.Screen
import android.util.Log

private const val TAG = "ConvosApp"

@Composable
fun ConvosApp(deepLinkUri: Uri? = null) {
    val navController = rememberNavController()

    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            handleDeepLink(uri, navController::navigate)
        }
    }

    ConvosNavigation(navController = navController)
}

private fun handleDeepLink(uri: Uri, navigate: (String) -> Unit) {
    Log.d(TAG, "Handling deep link: $uri")

    when {
        uri.scheme == "convos" && uri.host == "i" -> {
            val inviteCode = uri.pathSegments.firstOrNull()
            if (inviteCode != null) {
                Log.d(TAG, "Navigating to join conversation with invite code: $inviteCode")
                navigate("${Screen.NewConversation.route}?mode=manual&inviteCode=$inviteCode")
            }
        }
        uri.scheme == "https" && uri.host == "convos.app" && uri.pathSegments.firstOrNull() == "i" -> {
            val inviteCode = uri.pathSegments.getOrNull(1)
            if (inviteCode != null) {
                Log.d(TAG, "Navigating to join conversation with invite code: $inviteCode")
                navigate("${Screen.NewConversation.route}?mode=manual&inviteCode=$inviteCode")
            }
        }
        else -> {
            Log.w(TAG, "Unknown deep link format: $uri")
        }
    }
}
