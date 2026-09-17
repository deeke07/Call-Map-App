package com.callmap.agenttracker.presentation

/** Accessibility supports recovery; it must not gate an existing registration. */
internal fun startupDestination(
    registered: Boolean,
    corePermissionsGranted: Boolean,
    accessibilitySelected: Boolean,
    current: String?
): String = when {
    registered -> if (corePermissionsGranted) "home" else "permissions"
    corePermissionsGranted && accessibilitySelected -> "register"
    current == "permissions" || current == "register" -> current
    else -> "welcome"
}
