package conectate.com.data.repository

import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState
import conectate.com.data.model.CallInvite
import org.junit.Assert.assertEquals
import org.junit.Test

class CallCollisionPolicyTest {

    private val outgoing = ActiveCall(
        callId = "bbbb",
        title = "Llamada",
        initiatorUserId = "alice",
        connectionState = CallConnectionState.RINGING_OUTGOING,
        peerUserId = "bob"
    )

    @Test
    fun simultaneousDirectCalls_keepExactlyTheLexicallySmallerCall() {
        val incomingWinner = invite(callId = "aaaa")
        val incomingLoser = invite(callId = "cccc")

        assertEquals(
            IncomingCallDisposition.YIELD_OUTGOING,
            resolveIncomingCallDisposition(outgoing, null, "alice", incomingWinner)
        )
        assertEquals(
            IncomingCallDisposition.REJECT_BUSY,
            resolveIncomingCallDisposition(outgoing, null, "alice", incomingLoser)
        )
    }

    @Test
    fun simultaneousDirectCalls_bothDevicesChooseTheSameCall() {
        val aliceCall = outgoing.copy(
            callId = "aaaa",
            initiatorUserId = "alice",
            peerUserId = "bob"
        )
        val bobCall = outgoing.copy(
            callId = "bbbb",
            initiatorUserId = "bob",
            peerUserId = "alice"
        )

        val aliceDecision = resolveIncomingCallDisposition(
            activeCall = aliceCall,
            pendingIncoming = null,
            currentUserId = "alice",
            incoming = invite(callId = bobCall.callId, callerId = "bob")
        )
        val bobDecision = resolveIncomingCallDisposition(
            activeCall = bobCall,
            pendingIncoming = null,
            currentUserId = "bob",
            incoming = invite(callId = aliceCall.callId, callerId = "alice")
        )

        assertEquals(IncomingCallDisposition.REJECT_BUSY, aliceDecision)
        assertEquals(IncomingCallDisposition.YIELD_OUTGOING, bobDecision)
    }

    @Test
    fun unrelatedCaller_isRejectedWithoutReplacingTheActiveCall() {
        assertEquals(
            IncomingCallDisposition.REJECT_BUSY,
            resolveIncomingCallDisposition(
                outgoing,
                null,
                "alice",
                invite(callId = "aaaa", callerId = "charlie")
            )
        )
    }

    @Test
    fun duplicatePush_isIgnoredAndSecondPendingCallIsBusy() {
        val pending = invite(callId = "pending")
        assertEquals(
            IncomingCallDisposition.IGNORE_DUPLICATE,
            resolveIncomingCallDisposition(null, pending, "alice", pending)
        )
        assertEquals(
            IncomingCallDisposition.REJECT_BUSY,
            resolveIncomingCallDisposition(null, pending, "alice", invite(callId = "other"))
        )
    }

    private fun invite(callId: String, callerId: String = "bob") = CallInvite(
        callId = callId,
        title = "Llamada",
        callerUserId = callerId,
        callerName = callerId,
        contextType = "dm"
    )
}
