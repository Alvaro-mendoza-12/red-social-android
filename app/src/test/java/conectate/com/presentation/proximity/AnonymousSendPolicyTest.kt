package conectate.com.presentation.proximity

import org.junit.Assert.*
import org.junit.Test

class AnonymousSendPolicyTest {
    @Test fun thresholdsMatchServerContract() {
        for ((type, count) in listOf("audio" to 3,"sticker" to 6,"image" to 9)) {
            assertFalse(anonymousMediaUnlocked(count-1,type))
            assertTrue(anonymousMediaUnlocked(count,type))
            assertTrue(anonymousMediaUnlocked(count+1,type))
        }
        assertFalse(anonymousMediaUnlocked(100,"video"))
    }
    @Test fun immutableTargetRejectsChangedSessionAccountClosedStateAndGeneration() {
        val target = AnonymousSendIdentity("accountA","sessionA",1L,"client-id")
        assertTrue(target.isCurrent("accountA","sessionA","active",1L))
        assertFalse(target.isCurrent("accountA","sessionB","active",1L))
        assertFalse(target.isCurrent("accountB","sessionA","active",1L))
        assertFalse(target.isCurrent("accountA","sessionA","ended",1L))
        assertFalse(target.isCurrent("accountA","sessionA","active",3L)) // A→B→A
        assertEquals("client-id", target.clientMessageId)
    }
    @Test fun presenceRequiresResumedTabValidAccountAndNoConversation() {
        assertTrue(shouldReceiveBottle(true,true,true,false))
        assertFalse(shouldReceiveBottle(true,false,true,false)) // STARTED/STOPPED
        assertFalse(shouldReceiveBottle(false,true,true,false))
        assertFalse(shouldReceiveBottle(true,true,false,false))
        assertFalse(shouldReceiveBottle(true,true,true,true))
    }
}
