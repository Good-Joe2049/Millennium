package com.millennium.app.features.steamdb.data

internal data class SteamDbAppInfo(
    val currentPlayers: Int?,
    val peakToday: Int?,
    val peakAll: Int?,
    val followers: Int?,
    val updatedAt: Long?,
)

internal data class SteamDbLowestPrice(
    val price: String,
    val discount: Int?,
    val limitedLabel: String?,
    val occurrences: Int?,
    val lastAt: Long?,
)

/** Only the public fields needed by features; never retain account config or tokens. */
internal data class SteamStorePage(
    val appId: Int,
    val currency: String?,
    val free: Boolean,
    val positiveReviews: Long?,
    val negativeReviews: Long?,
)
