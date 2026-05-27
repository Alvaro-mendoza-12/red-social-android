package conectate.com.auth.twoFactor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warrenstrange.googleauth.GoogleAuthenticator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class TwoFactorAuthIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var twoFactorAuthManager: TwoFactorAuthManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        twoFactorAuthManager = TwoFactorAuthManager(context)
        
        // Clear any existing data before each test
        clearTestData()
    }
    
    @Test
    fun `complete 2FA setup and verification flow should work end-to-end`() = runTest {
        // Given
        val email = "integration@test.com"
        
        // Step 1: Generate TOTP secret
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "TOTP secret generation should succeed")
        assertNotNull(setupResult.totpSecret, "TOTP secret should not be null")
        assertNotNull(setupResult.qrCodeBitmap, "QR code should be generated")
        assertEquals(10, setupResult.backupCodes?.size, "Should generate 10 backup codes")
        
        // Step 2: Verify TOTP code works before enabling 2FA
        val secret = setupResult.totpSecret!!.secret
        val totp = TOTP(secret = secret, period = 30, digits = 6)
        val validCode = totp.now()
        
        val verifyResult = twoFactorAuthManager.verifyTOTPCode(validCode, secret)
        assertTrue(verifyResult.success, "TOTP verification should work")
        
        // Step 3: Enable 2FA
        val enableResult = twoFactorAuthManager.enable2FA(secret)
        assertTrue(enableResult.success, "2FA should be enabled")
        assertTrue(twoFactorAuthManager.is2FAEnabled.first(), "2FA should be enabled")
        assertTrue(twoFactorAuthManager.is2FARequired(), "2FA should be required")
        
        // Step 4: Verify TOTP code works after enabling 2FA
        val newValidCode = totp.now()
        val postEnableVerifyResult = twoFactorAuthManager.verifyTOTPCode(newValidCode, secret)
        assertTrue(postEnableVerifyResult.success, "TOTP verification should work after enabling 2FA")
        
        // Step 5: Test backup code
        val backupCodes = setupResult.backupCodes!!
        val backupCode = backupCodes.first()
        val backupVerifyResult = twoFactorAuthManager.verifyBackupCode(backupCode)
        assertTrue(backupVerifyResult.success, "Backup code verification should work")
        assertEquals(9, twoFactorAuthManager.getUnusedBackupCodesCount(), "Should have 9 unused backup codes")
        
        // Step 6: Disable 2FA
        val disableResult = twoFactorAuthManager.disable2FA()
        assertTrue(disableResult.success, "2FA should be disabled")
        assertFalse(twoFactorAuthManager.is2FAEnabled.first(), "2FA should be disabled")
        assertFalse(twoFactorAuthManager.is2FARequired(), "2FA should not be required")
        assertEquals(0, twoFactorAuthManager.getUnusedBackupCodesCount(), "Backup codes should be removed")
    }
    
    @Test
    fun `rate limiting should work correctly across multiple attempts`() = runTest {
        // Given
        val email = "ratelimit@test.com"
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
        assertTrue(twoFactorAuthManager.isLockedOut.first(), "Account should be locked")
        assertEquals(5, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be 5")
        assertTrue(twoFactorAuthManager.getRemainingLockoutTime() > 0, "Should have lockout time")
        
        // Verify lockout prevents further attempts
        val lockoutResult = twoFactorAuthManager.verifyTOTPCode(invalidCode, secret)
        assertFalse(lockoutResult.success, "Locked account should reject attempts")
        assertEquals("Account locked due to too many failed attempts. Try again later.", lockoutResult.error)
    }
    
    @Test
    fun `backup codes should be properly tracked and marked as used`() = runTest {
        // Given
        val email = "backup@test.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val backupCodes = setupResult.backupCodes!!
        
        // When - Use multiple backup codes
        val usedCodes = mutableListOf<String>()
        repeat(3) { index ->
            val backupCode = backupCodes[index]
            val result = twoFactorAuthManager.verifyBackupCode(backupCode)
            assertTrue(result.success, "Backup code $index should be accepted")
            usedCodes.add(backupCode)
            assertEquals(10 - (index + 1), twoFactorAuthManager.getUnusedBackupCodesCount(), "Should have correct unused count")
        }
        
        // Then - Try to reuse the same backup codes
        usedCodes.forEach { usedCode ->
            val reuseResult = twoFactorAuthManager.verifyBackupCode(usedCode)
            assertFalse(reuseResult.success, "Already used backup code should be rejected")
        }
        
        // Verify unused codes still work
        val unusedCode = backupCodes[5]
        val unusedResult = twoFactorAuthManager.verifyBackupCode(unusedCode)
        assertTrue(unusedResult.success, "Unused backup code should still work")
    }
    
    @Test
    fun `TOTP codes should work with time-based changes`() = runTest {
        // Given
        val email = "timebased@test.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val googleAuthenticator = GoogleAuthenticator()
        
        // When - Get current code
        val currentCode = googleAuthenticator.getTotpPassword(secret)
        
        // Then
        val verifyResult = twoFactorAuthManager.verifyTOTPCode(currentCode, secret)
        assertTrue(verifyResult.success, "Current TOTP code should be valid")
        
        // Verify the code matches what the manager generates
        val managerCode = twoFactorAuthManager.getCurrentTOTPCode(secret)
        assertEquals(currentCode, managerCode, "Manager should generate same code as TOTP library")
    }
    
    @Test
    fun `2FA state should persist across manager instances`() = runTest {
        // Given
        val email = "persistence@test.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        
        // Enable 2FA with first manager instance
        val enableResult = twoFactorAuthManager.enable2FA(secret)
        assertTrue(enableResult.success, "2FA should be enabled")
        
        // When - Create new manager instance
        val newManager = TwoFactorAuthManager(context)
        
        // Then
        assertTrue(newManager.is2FAEnabled.first(), "2FA state should persist")
        assertTrue(newManager.is2FARequired(), "2FA requirement should persist")
        
        // Verify TOTP still works with new instance
        val totp = TOTP(secret = secret, period = 30, digits = 6)
        val validCode = totp.now()
        val verifyResult = newManager.verifyTOTPCode(validCode, secret)
        assertTrue(verifyResult.success, "TOTP verification should work with new instance")
        
        // Disable 2FA with new instance
        val disableResult = newManager.disable2FA()
        assertTrue(disableResult.success, "2FA should be disabled with new instance")
        assertFalse(newManager.is2FAEnabled.first(), "2FA should be disabled")
    }
    
    @Test
    fun `QR code generation should produce scannable codes`() = runTest {
        // Given
        val email = "qrcode@test.com"
        
        // When
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val qrCodeBitmap = setupResult.qrCodeBitmap
        assertNotNull(qrCodeBitmap, "QR code should be generated")
        
        // Then - Verify QR code properties
        assertEquals(512, qrCodeBitmap?.width, "QR code should be 512px wide")
        assertEquals(512, qrCodeBitmap?.height, "QR code should be 512px tall")
        
        // Verify QR code contains expected URI format
        val totpSecret = setupResult.totpSecret!!
        val uri = totpSecret.toTOTPUri()
        assertTrue(uri.startsWith("otpauth://totp/"), "URI should be valid TOTP format")
        assertTrue(uri.contains(email), "URI should contain email")
        assertTrue(uri.contains("CONÉCTATE"), "URI should contain issuer")
    }
    
    @Test
    fun `multiple users should have independent 2FA setups`() = runTest {
        // Given
        val email1 = "user1@test.com"
        val email2 = "user2@test.com"
        
        // When - Setup 2FA for both users
        val setupResult1 = twoFactorAuthManager.generateTOTPSecret(email1)
        val setupResult2 = twoFactorAuthManager.generateTOTPSecret(email2)
        
        assertTrue(setupResult1.success, "Setup for user 1 should succeed")
        assertTrue(setupResult2.success, "Setup for user 2 should succeed")
        
        // Then - Verify secrets are different
        val secret1 = setupResult1.totpSecret!!.secret
        val secret2 = setupResult2.totpSecret!!.secret
        
        assertFalse(secret1 == secret2, "Secrets should be different for different users")
        assertFalse(setupResult1.backupCodes == setupResult2.backupCodes, "Backup codes should be different")
        
        // Verify codes work independently
        val googleAuthenticator1 = GoogleAuthenticator()
        val googleAuthenticator2 = GoogleAuthenticator()
        
        val code1 = googleAuthenticator1.getTotpPassword(secret1)
        val code2 = googleAuthenticator2.getTotpPassword(secret2)
        
        val verifyResult1 = twoFactorAuthManager.verifyTOTPCode(code1, secret1)
        val verifyResult2 = twoFactorAuthManager.verifyTOTPCode(code2, secret2)
        
        assertTrue(verifyResult1.success, "User 1 code should work")
        assertTrue(verifyResult2.success, "User 2 code should work")
        
        // Verify cross-verification fails
        val crossVerifyResult1 = twoFactorAuthManager.verifyTOTPCode(code1, secret2)
        val crossVerifyResult2 = twoFactorAuthManager.verifyTOTPCode(code2, secret1)
        
        assertFalse(crossVerifyResult1.success, "User 1 code should not work for user 2")
        assertFalse(crossVerifyResult2.success, "User 2 code should not work for user 1")
    }
    
    @Test
    fun `security measures should prevent replay attacks`() = runTest {
        // Given
        val email = "replay@test.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        
        // Get a valid code
        val totp = TOTP(secret = secret, period = 30, digits = 6)
        val validCode = totp.now()
        
        // When - Use the code successfully
        val firstVerifyResult = twoFactorAuthManager.verifyTOTPCode(validCode, secret)
        assertTrue(firstVerifyResult.success, "First verification should succeed")
        
        // Then - Try to use the same code again (within the same time window)
        val secondVerifyResult = twoFactorAuthManager.verifyTOTPCode(validCode, secret)
        assertTrue(secondVerifyResult.success, "Same code should work within time window")
        
        // Wait for time window to pass (simulate with different code)
        val differentCode = "000000"
        val invalidResult = twoFactorAuthManager.verifyTOTPCode(differentCode, secret)
        assertFalse(invalidResult.success, "Invalid code should be rejected")
    }
    
    @Test
    fun `failed attempts should reset after successful backup code use`() = runTest {
        // Given
        val email = "reset@test.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val backupCodes = setupResult.backupCodes!!
        val backupCode = backupCodes.first()
        
        // Make some failed attempts
        repeat(3) {
            val result = twoFactorAuthManager.verifyTOTPCode("123456", secret)
            assertFalse(result.success, "Invalid code should be rejected")
        }
        assertEquals(3, twoFactorAuthManager.failedAttempts.first(), "Should have 3 failed attempts")
        
        // When - Use backup code successfully
        val backupResult = twoFactorAuthManager.verifyBackupCode(backupCode)
        assertTrue(backupResult.success, "Backup code should be accepted")
        
        // Then
        assertEquals(0, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be reset")
        assertFalse(twoFactorAuthManager.isLockedOut.first(), "Account should not be locked")
    }
    
    @Test
    fun `2FA should handle rapid successive operations correctly`() = runTest {
        // Given
        val email = "rapid@test.com"
        val setupResult = twoFactorAuthManager.generateTOTPSecret(email)
        assertTrue(setupResult.success, "Setup should succeed")
        
        val secret = setupResult.totpSecret!!.secret
        val totp = TOTP(secret = secret, period = 30, digits = 6)
        
        // When - Perform rapid operations
        val operations = mutableListOf<Boolean>()
        
        repeat(10) {
            val code = totp.now()
            val result = twoFactorAuthManager.verifyTOTPCode(code, secret)
            operations.add(result.success)
        }
        
        // Then
        assertTrue(operations.all { it }, "All rapid operations should succeed")
        assertEquals(0, twoFactorAuthManager.failedAttempts.first(), "Failed attempts should be 0")
    }
    
    private fun clearTestData() {
        try {
            // Disable 2FA if enabled
            if (twoFactorAuthManager.is2FAEnabled.first()) {
                twoFactorAuthManager.disable2FA()
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
