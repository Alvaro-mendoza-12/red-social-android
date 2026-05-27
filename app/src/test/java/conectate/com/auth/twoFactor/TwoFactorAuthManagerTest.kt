package conectate.com.auth.twoFactor

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TwoFactorAuthManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("two_factor_secure_prefs_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `generateTOTPSecret stores pending setup for active identity`() {
        val manager = TwoFactorAuthManager(
            context = context,
            currentUserId = "user-1",
            currentUserEmail = "User@Test.com"
        )

        val result = manager.generateTOTPSecret("User@Test.com")

        assertTrue(result.success)
        assertNotNull(result.secret)
        assertTrue(result.secret!!.length >= 16)
        assertTrue(result.otpauthUrl!!.startsWith("otpauth://totp/"))
        assertTrue(result.qrCodeUrl!!.contains("create-qr-code"))
        assertEquals(8, result.backupCodes.size)
        assertEquals(result.secret, manager.getPendingSecret())
        assertEquals(8, manager.getPendingBackupCodes().size)
    }

    @Test
    fun `enable and disable 2FA toggles stored state`() {
        val manager = TwoFactorAuthManager(
            context = context,
            currentUserId = "user-2",
            currentUserEmail = "user2@test.com"
        )
        val setup = manager.generateTOTPSecret("user2@test.com")

        val enabled = manager.enable2FA(setup.secret!!)

        assertTrue(enabled.success)
        assertTrue(manager.is2FAEnabled())
        assertEquals(setup.secret, manager.getStoredSecret())

        val disabled = manager.disable2FA()

        assertTrue(disabled.success)
        assertFalse(manager.is2FAEnabled())
        assertEquals(null, manager.getStoredSecret())
        assertEquals(0, manager.getUnusedBackupCodesCount())
    }

    @Test
    fun `backup code verification consumes each code once`() {
        val manager = TwoFactorAuthManager(
            context = context,
            currentUserId = "user-3",
            currentUserEmail = "user3@test.com"
        )
        val setup = manager.generateTOTPSecret("user3@test.com")
        manager.enable2FA(setup.secret!!)
        val code = setup.backupCodes.first()

        val firstUse = manager.verifyBackupCode(code)
        val secondUse = manager.verifyBackupCode(code)

        assertTrue(firstUse.success)
        assertFalse(secondUse.success)
        assertEquals(7, manager.getUnusedBackupCodesCount())
    }

    @Test
    fun `invalid TOTP attempts lock active identity`() = runTest {
        val manager = TwoFactorAuthManager(
            context = context,
            currentUserId = "user-4",
            currentUserEmail = "user4@test.com"
        )
        val setup = manager.generateTOTPSecret("user4@test.com")
        manager.enable2FA(setup.secret!!)

        repeat(5) {
            assertFalse(manager.verifyTOTPCode("123456", setup.secret!!).success)
        }

        assertEquals(5, manager.failedAttempts.first())
        assertTrue(manager.isLockedOut.first())
        assertTrue(manager.getRemainingLockoutTime() > 0L)
    }

    @Test
    fun `enable and disable fail without an active identity`() {
        val manager = TwoFactorAuthManager(
            context = context,
            currentUserId = null,
            currentUserEmail = null
        )

        assertFalse(manager.enable2FA("ABCDEF234567").success)
        assertFalse(manager.disable2FA().success)
        assertFalse(manager.is2FAEnabled())
    }
}
