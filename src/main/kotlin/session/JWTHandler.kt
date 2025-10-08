package me.ezar.anemon.session

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import me.ezar.anemon.routes.dto.ExposedUserProfile
import me.ezar.anemon.routes.dto.UserProfilePrincipal
import me.ezar.anemon.services.UserService

fun ExposedUserProfile.generateToken(): String {
    return JWT.create()
        .withAudience("jwt-audience")
        .withIssuer("ezar.iago87@gmail.com")
        .withClaim("uid", this.uid)
        .withClaim("ver", this.tokenVersion)
        .sign(Algorithm.HMAC256("anemonindonesia123"))
}

fun Application.configureJWT(userService: UserService) {
    environment.config
    val jwtAudience = "jwt-audience"
    val jwtDomain = "ezar.iago87@gmail.com"
    val jwtRealm = "Anemon Indonesia Realm"
    val jwtSecret = "anemonindonesia123"

    authentication {
        jwt {
            realm = jwtRealm
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtDomain)
                    .build()
            )
            validate { credential ->
                val uid = credential.payload.getClaim("uid").asInt()
                val tokenVersion = credential.payload.getClaim("ver").asInt() ?: return@validate null

                val userProfile =
                    userService.findByUid(uid) ?: return@validate null

                if (userProfile.tokenVersion != tokenVersion) {
                    return@validate null
                }

                UserProfilePrincipal(userProfile.toUserProfile(), credential.payload)
            }
        }
    }
}