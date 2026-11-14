package conectate.com.presentation.live

import conectate.com.data.model.LiveComment
import conectate.com.data.supabase.AuthSessionMutationGuard
import org.junit.Assert.*
import org.junit.Test

class LiveToolAccountVisitTest {
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        fun visit(live: String = "live") = captureLiveToolAccountVisit(
            live, { guard.captureAccountLease { account } }, { guard.accountVisitChanges.value }, guard::commitForLease,
        )
        fun aba() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
    }

    @Test fun accountChangeHidesOpenToolsBeforeAnyRecomposition() {
        val f = Fixture()
        val dialogs = LiveToolDialogState(f.visit())
        dialogs.openGame(); dialogs.openPoints(); dialogs.selectComment(LiveComment(content = "privado"))
        assertTrue(dialogs.gameVisible); assertTrue(dialogs.pointsVisible); assertNotNull(dialogs.selectedComment)
        f.aba()
        assertFalse(dialogs.gameVisible); assertFalse(dialogs.pointsVisible); assertNull(dialogs.selectedComment)
    }

    @Test fun queuedOpenAndSubmitCannotBorrowTheReturningAccountsVisit() {
        val f = Fixture()
        val visit = f.visit()
        val dialogs = LiveToolDialogState(visit)
        val queuedOpen = dialogs::openPoints
        var dispatched = false
        val queuedSubmit = { visit.run { dispatched = true } }
        f.aba(); queuedOpen(); queuedSubmit()
        assertFalse(dialogs.pointsVisible); assertFalse(dispatched)
        assertNotEquals(visit.key, f.visit().key)
    }

    @Test fun lateDismissalCannotCloseTheNewVisitsPanel() {
        val f = Fixture()
        val old = LiveToolDialogState(f.visit())
        old.openPoints()
        val lateDismiss = old::closeAll
        f.aba()
        val current = LiveToolDialogState(f.visit())
        current.openPoints(); current.openGame()
        lateDismiss()
        assertTrue(current.pointsVisible); assertTrue(current.gameVisible)
        assertFalse(old.pointsVisible)
    }

    @Test fun tokenRenewalKeepsTheVisitAndOpenPanel() {
        val f = Fixture()
        val visit = f.visit()
        val dialogs = LiveToolDialogState(visit)
        dialogs.openPoints()
        f.guard.commitIfCurrent(f.guard.snapshot()) { }
        assertEquals(visit.key, f.visit().key)
        assertTrue(dialogs.pointsVisible)
    }

    @Test fun aSignedOutUserCannotOpenTools() {
        val f = Fixture().apply { account = null }
        val dialogs = LiveToolDialogState(f.visit())
        dialogs.openGame(); dialogs.openPoints(); dialogs.selectComment(LiveComment())
        assertFalse(dialogs.gameVisible); assertFalse(dialogs.pointsVisible); assertNull(dialogs.selectedComment)
    }

    @Test fun invalidationDuringCaptureCannotPoisonTheReturningVisitsKey() {
        val f = Fixture()
        val stale = captureLiveToolAccountVisit("live", { f.guard.captureAccountLease { f.account } },
            { f.guard.accountVisitChanges.value }, { owner, action -> f.aba(); f.guard.commitForLease(owner, action) })
        val fresh = f.visit()
        assertFalse(stale.isCurrent)
        assertNotEquals(stale.key, fresh.key)
        val currentDialogs = LiveToolDialogState(fresh)
        currentDialogs.openPoints()
        assertTrue(currentDialogs.pointsVisible)
    }
}
