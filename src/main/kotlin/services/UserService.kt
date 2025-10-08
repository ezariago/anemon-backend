package me.ezar.anemon.services

import me.ezar.anemon.routes.dto.AccountVehiclePreference
import me.ezar.anemon.routes.dto.ExposedUserProfile
import me.ezar.anemon.routes.dto.LoginRequest
import me.ezar.anemon.routes.dto.UserCreateRequest
import me.ezar.anemon.utils.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlin.random.Random

class UserService(database: Database) {
    object Users : Table() {
        val uid = integer("uid").autoIncrement()
        val name = varchar("name", length = 50)
        val email = varchar("email", length = 100).uniqueIndex()
        val nik = varchar("nik", length = 20)
        val passwordHash = varchar("password_hash", length = 100)
        val currentRole = varchar("current_role", length = 20)
        val profilePictureId = varchar("profile_picture_id", length = 50)
        val vehicleImageId = varchar("vehicle_image_id", length = 50).nullable()
        val tokenVersion = integer("token_version").default(0)

        override val primaryKey = PrimaryKey(uid)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Users)
        }
    }

    suspend fun login(credentials: LoginRequest): ExposedUserProfile? = dbQuery {
        val userRow = Users.selectAll().where { Users.email eq credentials.email }.singleOrNull()
            ?: return@dbQuery null

        if (BCrypt.checkpw(credentials.password, userRow[Users.passwordHash])) {
            val newVersion = Random.nextInt()
            Users.update({ Users.uid eq userRow[Users.uid] }) {
                it[tokenVersion] = newVersion
            }
            rowToProfile(userRow).copy(tokenVersion = newVersion)
        } else {
            null
        }
    }

    suspend fun create(userRequest: UserCreateRequest, vehicleFilename: String?, profileFilename: String): Int =
        dbQuery {
            Users.insert {
                it[name] = userRequest.name
                it[email] = userRequest.email
                it[nik] = userRequest.nik
                it[passwordHash] = BCrypt.hashpw(userRequest.password, BCrypt.gensalt())
                it[currentRole] = userRequest.vehiclePreference.name
                it[profilePictureId] = profileFilename
                it[vehicleImageId] = vehicleFilename ?: ""
            }[Users.uid]
        }

    suspend fun findByUid(uid: Int): ExposedUserProfile? {
        return dbQuery {
            Users.selectAll()
                .where { Users.uid eq uid }
                .map { rowToProfile(it) }
                .singleOrNull()
        }
    }

    private fun rowToProfile(row: ResultRow): ExposedUserProfile {
        return ExposedUserProfile(
            uid = row[Users.uid],
            name = row[Users.name],
            email = row[Users.email],
            nik = row[Users.nik],
            vehiclePreference = AccountVehiclePreference.valueOf(row[Users.currentRole]),
            profilePictureId = row[Users.profilePictureId],
            vehicleImageId = row[Users.vehicleImageId],
            tokenVersion = row[Users.tokenVersion]
        )
    }
}