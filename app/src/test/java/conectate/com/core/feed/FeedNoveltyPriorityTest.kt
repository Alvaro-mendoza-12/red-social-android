package conectate.com.core.feed

import org.junit.Assert.*
import org.junit.Test

class FeedNoveltyPriorityTest {
    @Test fun historyNeverEmptiesAnOtherwiseEligibleCatalog() {
        assertEquals(listOf("a","b"),prioritizeFeedCandidates(listOf("a","b"),setOf("a","b")) { it })
    }
    @Test fun unseenCandidatesComeFirstWithNoDuplicates() {
        assertEquals(listOf("new","a","b"),prioritizeFeedCandidates(listOf("a","new","NEW","b"," "),setOf("a","b")) { it })
    }
    @Test fun newVisitChangesTheFirstItemWhenItsPriorityHasAlternatives() {
        assertEquals(listOf("b","a","c"),prioritizeFeedCandidates(listOf("a","b","c"),setOf("a","b","c"),"a") { it })
    }
    @Test fun aSoleUnseenItemStaysBeforeSeenAlternatives() {
        assertEquals(listOf("new","old"),prioritizeFeedCandidates(listOf("new","old"),setOf("old"),"new") { it })
    }
    @Test fun priorityUsesOnlyTheProvidedAccountSnapshotAndIsStable() {
        val items=listOf("a","b");val a=prioritizeFeedCandidates(items,setOf("a")) { it }
        assertEquals(listOf("b","a"),a);assertEquals(a,prioritizeFeedCandidates(items,setOf("a")) { it })
        assertEquals(listOf("a","b"),prioritizeFeedCandidates(items,setOf("b")) { it })
    }

    @Test fun recentlyViewedItemsWaitBehindOlderCandidates() {
        val items = (1..12).map { "v$it" }
        val result = prioritizeFeedCandidates(items, items.toSet(), recentIds = items.reversed()) { it }
        assertEquals(items.take(6), result.take(6))
        assertEquals(items.toSet(), result.toSet())
        assertEquals(12, result.size)
    }
    @Test fun cooldownNeverHidesUnseenContentOrEmptiesATinyCatalog() {
        assertEquals(listOf("new", "older", "recent"), prioritizeFeedCandidates(
            listOf("recent", "older", "new"), setOf("recent", "older"), recentIds = listOf("recent", "older"),
        ) { it })
        assertEquals(listOf("only"), prioritizeFeedCandidates(
            listOf("only"), setOf("only"), recentIds = listOf("only"),
        ) { it })
    }
    @Test fun previousFirstDoesNotPromoteACoolingItem() {
        val result = prioritizeFeedCandidates(listOf("a", "b", "c", "d"), setOf("a", "b", "c", "d"), "a",
            recentIds = listOf("d", "c")) { it }
        assertEquals(listOf("b", "a", "c", "d"), result)
    }
}
