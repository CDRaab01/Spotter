package com.spotter.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The four bottom-bar destinations. [route] is the NavHost route *pattern* (used for selection
 * matching); [navRoute] is what actually gets navigated (Coach needs its optional-arg form).
 */
enum class TopLevelDestination(
    val route: String,
    val navRoute: String,
    val label: String,
    val icon: ImageVector,
    val iconOutlined: ImageVector,
) {
    HOME(
        route = Screen.Home.route,
        navRoute = Screen.Home.route,
        label = "Home",
        icon = Icons.Filled.Home,
        iconOutlined = Icons.Outlined.Home,
    ),
    CALENDAR(
        route = Screen.Calendar.route,
        navRoute = Screen.Calendar.route,
        label = "Calendar",
        icon = Icons.Filled.CalendarMonth,
        iconOutlined = Icons.Outlined.CalendarMonth,
    ),
    COACH(
        route = Screen.AiChat.route,
        navRoute = Screen.AiChat.createRoute(),
        label = "Coach",
        icon = Icons.AutoMirrored.Filled.Chat,
        iconOutlined = Icons.AutoMirrored.Outlined.Chat,
    ),
    PROGRESS(
        route = Screen.Progress.route,
        navRoute = Screen.Progress.route,
        label = "Progress",
        icon = Icons.Filled.ShowChart,
        iconOutlined = Icons.Outlined.ShowChart,
    ),
}
