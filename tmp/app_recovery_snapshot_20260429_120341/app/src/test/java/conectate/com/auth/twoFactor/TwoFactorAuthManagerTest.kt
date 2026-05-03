package conectate.com.auth.twoFactor

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.WriterException
import com.warrenstrange.googleauth.GoogleAuthenticator
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TwoFactorAuthManagerTest {
    
    private lateinit var context: Context
    private lateinit var twoFactorAuthManager: TwoFactorAuthManager
    
    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        twoFactorAuthManager = TwoFactorAuthManager(context)
        
        // Clear any existing data before each test
        clearTestData()
    }
    
    @Test
    fun `generateTOTPSecret should create valid TOTP secret with QR code`() = runTest {
        // Given
        val email = "test@example.com"
        
        // When
        val result = twoFactorAuthManager.generateTOTPSecret(email)
        
        // Then
        assertTrue(result.success, "TOTP secret generation should succeed")
        assertNotNull(result.totpSecret, "TOTP secret should not be null")
        assertNotNull(result.qrCodeBitmap, "QR code bitmap should not be null")
        assertNotNull(result.backupCodes, "Backup codes should not be null")
        assertEquals(10, result.backupCodes?.size, "Should generate 10 backup codes")
        assertEquals(email, result.totpSecret?.accountName, "Account name should match email")
        assertEquals("CONÉCTATE", result.totpSecret?.issuer, "Issuer should be CONÉCTATE")
        assertEquals(6, result.totpSecret?.digits, "Should use 6 digits")
        assertEquals(30, result.totpSecret?.period, "Should use 30 second period")
    }
    
    @Test
    fun `verifyTOTPCode should accept valid code`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val googleAuthenticator = GoogleAuthenticator()
        val validCode = googleAuthenticator.getTotpPassword(secret)
        
        // When
        val result = twoFactorAuthManager.verifyTOTPCode(validCode, secret)
        
        // Then
        assertTrue(result.success, "Valid TOTP code should be accepted")
        assertEquals(0, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be reset")
    }
    
    @Test
    fun `verifyTOTPCode should reject invalid code`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val invalidCode = "123456"
        
        // When
        val result = twoFactorAuthManager.verifyTOTPCode(invalidCode, secret)
        
        // Then
        assertFalse(result.success, "Invalid TOTP code should be rejected")
        assertEquals(1, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should increment")
        assertEquals("Invalid TOTP code", result.error, "Should return appropriate error message")
    }
    
    @Test
    fun `verifyBackupCode should accept valid unused backup code`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val backupCodes = setupResult.backupCodes!!
        val validBackupCode = backupCodes.first()
        
        // When
        val result = twoFactorAuthManager.verifyBackupCode(validBackupCode)
        
        // Then
        assertTrue(result.success, "Valid backup code should be accepted")
        assertEquals(0, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be reset")
        assertEquals(9, twoFactorAuthManager.getUnusedBackupCodesCount(), "Should have 9 unused backup codes left")
    }
    
    @Test
    fun `verifyBackupCode should reject already used backup code`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val backupCodes = setupResult.backupCodes!!
        val validBackupCode = backupCodes.first()
        
        // Use the backup code once
        val firstResult = twoFactorAuthManager.verifyBackupCode(validBackupCode)
        assertTrue(firstResult.success, "First use should succeed")
        
        // When
        val secondResult = twoFactorAuthManager.verifyBackupCode(validBackupCode)
        
        // Then
        assertFalse(secondResult.success, "Already used backup code should be rejected")
        assertEquals(1, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should increment")
        assertEquals("Invalid or already used backup code", secondResult.error, "Should return appropriate error message")
    }
    
    @Test
    fun `enable2FA should enable 2FA successfully`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        
        // When
        val result = twoFactorAuthManager.enable2FA(secret)
        
        // Then
        assertTrue(result.success, "2FA should be enabled successfully")
        assertTrue(twoFactorAuthManager.is2FAEnabled.first(), "2FA should be enabled")
        assertTrue(twoFactorAuthManager.is2FARequired(), "2FA should be required for login")
    }
    
    @Test
    fun `disable2FA should disable 2FA successfully`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val enableResult = twoFactorAuthManager.enable2FA(secret)
        assertTrue(enableResult.success, "2FA should be enabled first")
        
        // When
        val result = twoFactorAuthManager.disable2FA()
        
        // Then
        assertTrue(result.success, "2FA should be disabled successfully")
        assertFalse(twoFactorAuthManager.is2FAEnabled.first(), "2FA should be disabled")
        assertFalse(twoFactorAuthManager.is2FARequired(), "2FA should not be required for login")
        assertEquals(0, twoFactorAuthManager.getUnusedBackupCodesCount(), "Backup codes should be removed")
    }
    
    @Test
    fun `multiple failed attempts should lock account`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val invalidCode = "123456"
        
        // When - Make 5 failed attempts
        repeat(5) {
            val result = twoFactorAuthManager.verifyTOTPCode(invalidCode, secret)
            assertFalse(result.success, "Invalid code should be rejected")
        }
        
        // Then
        assertTrue(twoFactorAuthManager.isLockedOut.first(), "Account should be locked after 5 failed attempts")
        assertEquals(5, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be 5")
        assertTrue(twoFactorAuthManager.getRemainingLockoutTime() > 0, "Should have remaining lockout time")
    }
    
    @Test
    fun `locked account should reject verification attempts`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        
        // Lock the account
        repeat(5) {
            twoFactorAuthManager.verifyTOTPCode("123456", secret)
        }
        
        assertTrue(twoFactorAuthManager.isLockedOut.first(), "Account should be locked")
        
        // When
        val result = twoFactorAuthManager.verifyTOTPCode("123456", secret)
        
        // Then
        assertFalse(result.success, "Locked account should reject verification attempts")
        assertEquals("Account locked due to too many failed attempts. Try again later.", result.error)
    }
    
    @Test
    fun `getCurrentTOTPCode should return valid code`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        
        // When
        val currentCode = twoFactorAuthManager.getCurrentTOTPCode(secret)
        
        // Then
        assertNotNull(currentCode, "Current TOTP code should not be null")
        assertEquals(6, currentCode?.length, "TOTP code should be 6 digits")
        assertTrue(currentCode?.all { it.isDigit() } == true, "TOTP code should contain only digits")
    }
    
    @Test
    fun `TOTP secret should generate valid QR code URI`() = runTest {
        // Given
        val email = "test@example.com"
        
        // When
        val result = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(result.success, "Setup should succeed")
        
        val totpSecret = result.totpSecret!!
        val uri = totpSecret.toTOTPUri()
        
        // Then
        assertTrue(uri.startsWith("otpauth://totp/"), "URI should start with otpauth://totp/")
        assertTrue(uri.contains("CONÉCTATE"), "URI should contain issuer")
        assertTrue(uri.contains(email), "URI should contain account name")
        assertTrue(uri.contains("secret="), "URI should contain secret")
        assertTrue(uri.contains("algorithm=SHA1"), "URI should specify SHA1 algorithm")
        assertTrue(uri.contains("digits=6"), "URI should specify 6 digits")
        assertTrue(uri.contains("period=30"), "URI should specify 30 second period")
    }
    
    @Test
    fun `backup codes should be unique`() = runTest {
        // Given
        val email = "test@example.com"
        
        // When
        val result = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(result.success, "Setup should succeed")
        
        val backupCodes = result.backupCodes!!
        
        // Then
        val uniqueCodes = backupCodes.toSet()
        assertEquals(backupCodes.size, uniqueCodes.size, "All backup codes should be unique")
        assertTrue(backupCodes.all { it.length == 8 }, "All backup codes should be 8 digits")
        assertTrue(backupCodes.all { it.all { it.isDigit() } }, "All backup codes should contain only digits")
    }
    
    @Test
    fun `failed attempts should reset after successful verification`() = runTest {
        // Given
        val email = "test@example.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        
        // Make some failed attempts
        repeat(3) {
            twoFactorAuthManager.verifyTOTPCode("123456", secret)
        }
        assertEquals(3, twoFactorAuthManager.failedAttempts.first(), "Should have 3 failed attempts")
        
        // Get valid code
        val totp = TOTP(secret = secret, period = 30, digits = 6)
        val validCode = totp.now()
        
        // When
        val result = twoFactorAuthManager.verifyTOTPCode(validCode, secret)
        
        // Then
        assertTrue(result.success, "Valid code should be accepted")
        assertEquals(0, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be reset")
        assertFalse(twoFactorAuthManager.isLockedOut.first(), "Account should not be locked")
    }
    
    @Test
    fun `2FA should be disabled by default`() {
        // Then
        assertFalse(twoFactorAuthManager.is2FAEnabled.first(), "2FA should be disabled by default")
        assertFalse(twoFactorAuthManager.is2FARequired(), "2FA should not be required by default")
        assertEquals(0, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be 0 by default")
        assertFalse(twoFactorAuthManager.isLockedOut.first(), "Account should not be locked by default")
        assertEquals(0, twoFactorAuthManager.getRemainingLockoutTime(), "Lockout time should be 0 by default")
        assertEquals(0, twoFactorAuthManager.getUnusedBackupCodesCount(), "Should have 0 unused backup codes by default")
    }
    
    @Test
    fun `generateTOTPSecret should handle exceptions gracefully`() = runTest {
        // Given - Mock context to throw exception
        val mockContext = mockk<Context>()
        every { mockContext.applicationContext } throws RuntimeException("Mock exception")
        
        // When
        val mockManager = TwoFactorAuthManager(mockContext)
        val result = mockManager.generateTOTPSecret("test@example.com")
        
        // Then
        assertFalse(result.success, "Should handle exceptions gracefully")
        assertNotNull(result.error, "Should return error message")
        assertTrue(result.error?.contains("Failed to generate TOTP secret") == true, "Should contain appropriate error")
    }
    
    @Test
    fun `verifyTOTPCode should handle invalid secret gracefully`() = runTest {
        // Given
        val invalidSecret = "invalid_secret"
        val code = "123456"
        
        // When
        val result = twoFactorAuthManager.verifyTOTPCode(code, invalidSecret)
        
        // Then
        assertFalse(result.success, "Should handle invalid secret gracefully")
        assertNotNull(result.error, "Should return error message")
    }
    
    @Test
    fun `enable2FA should handle exceptions gracefully`() = runTest {
        // Given - Mock context to throw exception
        val mockContext = mockk<Context>()
        every { mockContext.applicationContext } throws RuntimeException("Mock exception")
        
        // When
        val mockManager = TwoFactorAuthManager(mockContext)
        val result = mockManager.enable2FA("test_secret")
        
        // Then
        assertFalse(result.success, "Should handle exceptions gracefully")
        assertNotNull(result.error, "Should return error message")
        assertTrue(result.error?.contains("Failed to enable 2FA") == true, "Should contain appropriate error")
    }
    
    @Test
    fun `disable2FA should handle exceptions gracefully`() = runTest {
        // Given - Mock context to throw exception
        val mockContext = mockk<Context>()
        every { mockContext.applicationContext } throws RuntimeException("Mock exception")
        
        // When
        val mockManager = TwoFactorAuthManager(mockContext)
        val result = mockManager.disable2FA()
        
        // Then
        assertFalse(result.success, "Should handle exceptions gracefully")
        assertNotNull(result.error, "Should return error message")
        assertTrue(result.error?.contains("Failed to disable 2FA") == true, "Should contain appropriate error")
    }
    
    private fun clearTestData() {
        try {
            // Clear encrypted preferences
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setEncryptionScheme(MasterKey.EncryptionScheme.AES256_GCM_HKDF_SHA256)
                .build()
            
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "two_factor_auth_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            encryptedPrefs.edit().clear().apply()
            
            val totpPrefs = EncryptedSharedPreferences.create(
                context,
                "totp_secret_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            totpPrefs.edit().clear().apply()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
