package com.millennium.app.features.steamdb.data

internal data class SteamDbAppInfo(
    val currentPlayers: Int?,
    val peakToday: Int?,
    val peakAll: Int?,
    val followers: Int?,
    val updatedAt: Long?,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

internal data class SteamDbLowestPrice(
    val price: String,
    val discount: Int?,
    /** SteamDB's l field: the lowest price seen during the limited period. */
    val limitedPrice: String?,
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
    val name: String?,
)
