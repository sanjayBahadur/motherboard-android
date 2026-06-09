package com.motherboard.focus.storage

data class InterventionSettings(
    val sessionLimit: Int = 10,
    val cooldownDurationMillis: Long = 5 * 60 * 1000L,
    val warningDurationMillis: Long = 2000L,
    val eventDebounceMillis: Long = 1000L,
    val youtubeShortsEnabled: Boolean = true,
    val blockingEnabled: Boolean = true,
    val shortsBlockedToday: Int = 0,
    val cooldownsTriggeredToday: Int = 0,
)
