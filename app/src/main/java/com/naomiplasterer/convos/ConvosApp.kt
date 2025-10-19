package com.naomiplasterer.convos

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.naomiplasterer.convos.navigation.ConvosNavigation

@Composable
fun ConvosApp() {
    val navController = rememberNavController()
    ConvosNavigation(navController = navController)
}
