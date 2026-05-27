package conectate.com.data.repository

import conectate.com.data.local.dao.DeviceTokenDao
import conectate.com.data.local.entity.DeviceTokenEntity
import conectate.com.data.supabase.AuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceTokenRepository @Inject constructor(
    private val deviceTokenDao: DeviceTokenDao,
    private val authService: AuthService
) {

    suspend fun saveToken(token: String): Boolean {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return false
        deviceTokenDao.insert(DeviceTokenEntity(id = userId, token = token))
        return true
    }

    suspend fun getToken(): String? {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return null
        return deviceTokenDao.getTokenByUserId(userId)?.token
    }

    suspend fun deleteToken(): Boolean {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return false
        deviceTokenDao.deleteByUserId(userId)
        return true
    }
}



