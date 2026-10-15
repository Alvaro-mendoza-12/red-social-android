package conectate.com.presentation.raffles

import org.junit.Assert.*
import org.junit.Test

class RaffleListWindowTest {
    private val participants = (1..80).map { "Persona $it" }
    @Test fun presetsAndCustomLimitAreBounded() {
        assertEquals(4, raffleDisplayLimit("4")); assertEquals(10, raffleDisplayLimit("10"))
        assertNull(raffleDisplayLimit("all")); assertEquals(27, raffleDisplayLimit("27"))
        assertEquals(10, raffleDisplayLimit("")); assertEquals(1, raffleDisplayLimit("0"))
        assertEquals(1000, raffleDisplayLimit("9999"))
    }
    @Test fun eightyParticipantsShowFourTenOrAllWithoutChangingSource() {
        for (limit in listOf(4,10,null)) {
            val result = raffleListWindow(participants, "", limit) { it }
            assertEquals(limit ?: 80, result.items.size); assertEquals(80,result.total); assertEquals(80,result.matching)
            assertEquals(participants.take(limit ?: 80), result.items)
        }
        assertEquals(80, participants.size)
    }
    @Test fun searchChecksEntireListBeforeApplyingLimit() {
        assertEquals(listOf("Persona 80"), raffleListWindow(participants, "persona 80",4) { it }.items)
        val matching = raffleListWindow(participants,"Persona 7",4) { it }
        assertEquals(11,matching.matching); assertEquals(listOf("Persona 7","Persona 70","Persona 71","Persona 72"),matching.items)
    }
    @Test fun accentsCaseAndUnicodeAreSearchable() {
        assertEquals(listOf("José Álvarez"), raffleListWindow(listOf("José Álvarez","María")," JOSE ALVAREZ ",10) { it }.items)
        assertEquals(listOf("Persona 80"), raffleListWindow(participants,"Ｐｅｒｓｏｎａ ８０",10) { it }.items)
    }
    @Test fun emptyAndNonMatchingListsStayEmpty() {
        assertTrue(raffleListWindow(participants,"inexistente",null) { it }.items.isEmpty())
        assertEquals(0,raffleListWindow(emptyList<String>(),"",4) { it }.total)
    }
}
