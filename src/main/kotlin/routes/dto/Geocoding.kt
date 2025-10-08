package me.ezar.anemon.routes.dto

import kotlinx.serialization.Serializable

// --- DTO client -> server ---
@Serializable
data class ReverseGeocodingRequest(
    val latitude: Double,
    val longitude: Double
)

// --- DTO for server -> client ---
@Serializable
data class ReverseGeocodingResponse(
    val formattedAddress: String
)