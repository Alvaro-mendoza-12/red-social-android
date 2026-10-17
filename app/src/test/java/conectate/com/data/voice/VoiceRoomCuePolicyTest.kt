package conectate.com.data.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceRoomCuePolicyTest {
    private val peer = CueParticipant("peer", "one", false)
    private fun room() = VoiceRoomCuePolicy("self").also { assertEquals(listOf(VoiceCue.JOIN), it.connect(false)); it.baseline(listOf(peer)) }
    @Test fun snapshotAndDuplicateJoinAreSilent() {
        val p = room(); assertTrue(p.join(peer).isEmpty())
        assertEquals(listOf(VoiceCue.JOIN), p.join(peer.copy(userId = "other", socketId = "two")))
        assertTrue(p.join(peer.copy(userId = "other", socketId = "two")).isEmpty())
    }
    @Test fun localAndRemoteMuteChangesFireOnce() {
        val p = room(); assertTrue(p.mute("peer", false).isEmpty())
        assertEquals(listOf(VoiceCue.MUTE), p.mute("peer", true)); assertTrue(p.mute("peer", true).isEmpty())
        assertEquals(listOf(VoiceCue.UNMUTE), p.mute("peer", false))
        assertEquals(listOf(VoiceCue.MUTE), p.mute("self", true)); assertEquals(listOf(VoiceCue.UNMUTE), p.mute("self", false))
        assertTrue(p.mute("unknown", true).isEmpty())
    }
    @Test fun quickReconnectAbsorbsLeaveAndRejoin() {
        val p = room(); p.leave("one", 1000); assertTrue(p.drain(1500).isEmpty())
        assertTrue(p.join(peer.copy(socketId = "new")).isEmpty()); assertTrue(p.drain(2000).isEmpty())
        p.leave("new", 2000); assertEquals(listOf(VoiceCue.LEAVE), p.drain(2800)); assertTrue(p.drain(3000).isEmpty())
    }
    @Test fun staleSocketCannotRemoveNewSocket() {
        val p = room(); p.join(peer.copy(socketId = "two")); p.leave("one", 0)
        assertTrue(p.drain(1000).isEmpty()); assertEquals(listOf(VoiceCue.MUTE), p.mute("peer", true))
    }
    @Test fun reconnectAndSnapshotsDoNotReplay() {
        val p = room(); p.leave("one", 0); p.suspend(); assertTrue(p.drain(1000).isEmpty())
        assertTrue(p.connect(false).isEmpty()); p.baseline(listOf(peer)); assertTrue(p.join(peer).isEmpty())
        assertEquals(listOf(VoiceCue.LEAVE), p.leaveSelf()); assertTrue(p.join(peer).isEmpty())
    }
    @Test fun unopenedRoomIsSilent() {
        val p = VoiceRoomCuePolicy("self"); p.baseline(listOf(peer))
        assertTrue(p.join(peer.copy(userId = "other")).isEmpty()); assertTrue(p.mute("peer", true).isEmpty()); assertTrue(p.leaveSelf().isEmpty())
    }
    @Test fun olderJoinWithoutMuteStateStillAnnouncesFirstActualChange() {
        for (muted in listOf(true, false)) {
            val p = room(); p.join(CueParticipant("older", "old", null))
            assertEquals(listOf(if (muted) VoiceCue.MUTE else VoiceCue.UNMUTE), p.mute("older", muted))
            assertTrue(p.mute("older", muted).isEmpty())
        }
    }
}
