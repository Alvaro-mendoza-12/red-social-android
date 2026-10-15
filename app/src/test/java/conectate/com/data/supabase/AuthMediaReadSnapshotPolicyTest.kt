package conectate.com.data.supabase

import org.junit.Assert.*
import org.junit.Test
import conectate.com.core.media.StorageReadSession
import java.io.IOException

class AuthMediaReadSnapshotPolicyTest {
    @Test fun normalizesCoherentPublishedSession() {
        val value = AuthMediaReadSnapshotPolicy.snapshot(" USER-A "," token.value ",3,3,false)!!
        assertEquals("user-a",value.identity.accountId)
        assertEquals(3L,value.identity.generation)
        assertEquals("token.value",value.accessToken)
    }
    @Test fun absentPublishedSessionNeverReadsFromDiskFallback() {
        assertNull(AuthMediaReadSnapshotPolicy.snapshot(null,"token",3,3,false))
        assertNull(AuthMediaReadSnapshotPolicy.snapshot("user-a",null,3,3,false))
        assertNull(AuthMediaReadSnapshotPolicy.snapshot(" ","token",3,3,false))
    }
    @Test fun generationChangedWhileCapturingSessionIsDenied() {
        assertNull(AuthMediaReadSnapshotPolicy.snapshot("user-a","token",3,4,false))
    }
    @Test fun registrationDoesNotExposePendingSession() {
        assertNull(AuthMediaReadSnapshotPolicy.snapshot("user-a","token",3,3,true))
    }
    @Test fun memoryIdentityGuardsRemainActiveDuringReadsAndLogout() {
        var account: String? = "user-a"
        var generation = 3L
        StorageReadSession.install { AuthMediaReadSnapshotPolicy.snapshot(account,"token",generation,generation,false) }
        try {
            val identity=StorageReadSession.snapshot()!!.identity
            repeat(1000) { StorageReadSession.requireCurrent(identity) }
            account="user-b"
            try { StorageReadSession.requireCurrent(identity); fail("Account switch must reject old bytes") } catch (_: IOException) {}
            account="user-a";generation++
            try { StorageReadSession.requireCurrent(identity); fail("Old generation must reject bytes") } catch (_: IOException) {}
            account=null
            assertNull(StorageReadSession.snapshot())
        } finally { StorageReadSession.install { null } }
    }
}
