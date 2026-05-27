package conectate.com

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class ConectateTestRunner : AndroidJUnitRunner() {

    override fun callApplicationOnCreate(app: Application) {
        prepareStableTestState(app)
        super.callApplicationOnCreate(app)
    }

    private fun prepareStableTestState(context: Context) {
        context.deleteDatabase("conectate_db")
        listOf(
            "auth_cache",
            "account_switch_secure_prefs",
            "multi_account_inbox",
            "notification_repository_cache",
            "privacy_policy_cache",
            "user_cache",
            "app_prefs"
        ).forEach(context::deleteSharedPreferences)

        context.getSharedPreferences(TEST_RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DISABLE_STARTUP_PROMPTS_KEY, true)
            .commit()
    }

    private companion object {
        const val TEST_RUNTIME_PREFS = "test_runtime_prefs"
        const val DISABLE_STARTUP_PROMPTS_KEY = "disable_startup_prompts"
    }
}
