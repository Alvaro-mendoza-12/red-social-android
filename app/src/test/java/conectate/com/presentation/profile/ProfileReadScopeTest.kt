package conectate.com.presentation.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileReadScopeTest {
    @Test fun switchingProfilesRejectsTheOldRead() {
        val scope = ProfileReadScope { "owner" }
        val old = scope.begin("one"); val current = scope.begin("two")
        assertFalse(scope.isCurrent(old)); assertTrue(scope.isCurrent(current))
        assertNull(scope.snapshotFor("one"))
    }
    @Test fun refreshingTheSameProfileRejectsItsPreviousRead() {
        val scope = ProfileReadScope { "owner" }
        val old = scope.begin("one"); val current = scope.begin("one")
        assertFalse(scope.isCurrent(old)); assertEquals(current, scope.snapshotFor("one"))
    }
    @Test fun switchingAccountsRejectsThePreviousAccountResponse() {
        var account: String? = "owner"
        val scope = ProfileReadScope { account }; val old = scope.begin("one")
        account = "other"
        assertFalse(scope.isCurrent(old)); assertNull(scope.snapshotFor("one"))
        assertTrue(scope.isCurrent(scope.begin("one")))
    }
    @Test fun signingOutRejectsThePendingResponse() {
        var account: String? = "owner"
        val scope = ProfileReadScope { account }; val old = scope.begin("one")
        account = null
        assertFalse(scope.isCurrent(old))
    }
}
