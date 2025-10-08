package me.ezar.anemon.routes.dto

import com.auth0.jwt.interfaces.Payload
import io.ktor.server.auth.jwt.*
import kotlinx.serialization.Serializable

// DTO client -> server ---

@Serializable
data class UserCreateRequest(
    val name: String,
    val email: String,
    val nik: String,
    val password: String,
    val vehiclePreference: AccountVehiclePreference,
    val profilePictureEncoded: String,
    val vehicleImageEncoded: String?
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

// --- DTO server -> client ---

@Serializable
data class UserProfile(
    val uid: Int,
    val name: String,
    val email: String,
    val nik: String,
    val profilePictureId: String,
    val vehicleImageId: String? = null,
    val vehiclePreference: AccountVehiclePreference,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserProfile) return false

        if (uid != other.uid) return false
        if (name != other.name) return false
        if (email != other.email) return false
        if (nik != other.nik) return false
        if (profilePictureId != other.profilePictureId) return false
        if (vehicleImageId != other.vehicleImageId) return false
        if (vehiclePreference != other.vehiclePreference) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result *= 31 * uid.hashCode()
        result = 31 * result + email.hashCode()
        result = 31 * result + nik.hashCode()
        result = 31 * result + profilePictureId.hashCode()
        result = 31 * result + (vehicleImageId?.hashCode() ?: 0)
        result = 31 * result + vehiclePreference.hashCode()
        return result
    }
}

@Serializable
data class LoginResponse(
    val token: String,
    val profile: UserProfile
)

@Serializable
data class ExposedUserProfile(
    val uid: Int,
    val name: String,
    val email: String,
    val nik: String,
    val vehiclePreference: AccountVehiclePreference,
    val profilePictureId: String,
    val vehicleImageId: String? = null,
    @kotlinx.serialization.Transient val tokenVersion: Int = 0
) {
    fun toUserProfile(): UserProfile {
        return UserProfile(uid, name, email, nik, profilePictureId, vehicleImageId, vehiclePreference)
    }
}

data class UserProfilePrincipal(
    val userProfile: UserProfile,
    val pl: Payload
) : JWTPayloadHolder(pl)

enum class AccountVehiclePreference {
    PASSENGER,
    CAR,
    MOTORCYCLE
}