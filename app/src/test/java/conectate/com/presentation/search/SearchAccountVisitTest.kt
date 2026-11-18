package conectate.com.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.SemanticSearchResult
import conectate.com.data.model.User
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.PublicContentSearchPage
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchAccountVisitTest {
    private val main = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val visits = AuthSessionMutationGuard()
    private val users = mockk<UserRepository>()
    private val posts = mockk<PostRepository>()
    private val auth = mockk<AuthService>()
    private val history = mockk<SearchHistoryStore>(relaxUnitFun = true)
    private val savedHistory = mutableMapOf("a" to listOf("Guardada A"), "b" to listOf("Guardada B"))
    private var account: String? = "a"
    private val result = SemanticSearchResult(id = "visible-post", title = "Un resultado")

    @Before fun start() {
        Dispatchers.setMain(main)
        every { auth.getCurrentUserId() } answers { account }
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        every { auth.accountVisitChanges } returns visits.accountVisitChanges
        every { auth.captureAccountLease() } answers { visits.captureAccountLease { account } }
        every { auth.commitForAccountLease(any(), any()) } answers { visits.commitForLease(firstArg(), secondArg()) }
        every { history.read(any()) } answers { savedHistory[firstArg<String?>()].orEmpty() }
        every { history.write(any(), any()) } answers {
            firstArg<String?>()?.let { savedHistory[it] = secondArg() }
        }
        coEvery { users.searchUsers(any()) } returns Result.success(listOf(User(id = "test-person")))
        coEvery { posts.searchPublicContentPage(any(), any(), any()) } returns
            Result.success(PublicContentSearchPage(listOf(result), 20))
    }

    @After fun stop() {
        store.clear()
        main.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(query: String = ""): SearchUsersViewModel =
        SearchUsersViewModel(users, posts, auth, history, SavedStateHandle(mapOf("query" to query))).also {
            store.put("search", it)
            main.scheduler.runCurrent()
        }

    private fun switchTo(value: String?) = visits.invalidate { account = value }
    private fun run() = main.scheduler.runCurrent()

    @Test fun aRelatedQueryStartsWithItsOriginalTextAndProducesResults() {
        val viewModel = model("café & anime")
        assertEquals("café & anime", viewModel.query.value)
        assertEquals(1, viewModel.initialCategory)
        assertEquals(listOf(result), viewModel.contentResults.value)
        assertEquals(listOf("test-person"), viewModel.searchResults.value.map(User::id))
        verify(exactly = 1) { history.write("a", listOf("café & anime", "Guardada A")) }
    }

    @Test fun anAccountVisitChangeClearsResultsAndLoadsTheNewHistory() {
        val viewModel = model("primera búsqueda")
        switchTo("b")
        run()
        assertEquals("b", viewModel.accountId.value)
        assertEquals("", viewModel.query.value)
        assertEquals(listOf("Guardada B"), viewModel.history.value)
        assertTrue(viewModel.contentResults.value.isEmpty())
        assertFalse(viewModel.hasMoreContent.value)
    }

    @Test fun returningToTheSameAccountClearsThePreviousVisit() {
        val viewModel = model("primera búsqueda")
        switchTo("b")
        switchTo("a")
        run()
        assertEquals("a", viewModel.accountId.value)
        assertEquals("", viewModel.query.value)
        assertEquals(listOf("primera búsqueda", "Guardada A"), viewModel.history.value)
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertTrue(viewModel.contentResults.value.isEmpty())
    }

    @Test fun aPendingReplyCannotPublishAfterTheAccountVisitChanges() {
        val reply = CompletableDeferred<Unit>()
        coEvery { posts.searchPublicContentPage(any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { reply.await() }
            Result.success(PublicContentSearchPage(listOf(result), 20))
        }
        val viewModel = model("en curso")
        try {
            assertTrue(viewModel.isLoading.value)
            switchTo("b")
            switchTo("a")
            reply.complete(Unit)
            run()
            assertTrue(viewModel.contentResults.value.isEmpty())
            assertFalse(viewModel.isLoading.value)
            assertFalse(viewModel.hasMoreContent.value)
        } finally {
            reply.complete(Unit)
            run()
        }
    }

    @Test fun stalePaginationBeforeTheObserverRunsCannotStartANewRead() {
        val viewModel = model("café")
        assertTrue(viewModel.hasMoreContent.value)
        switchTo("b")
        switchTo("a")
        viewModel.loadMore()
        run()
        coVerify(exactly = 0) { posts.searchPublicContentPage(any(), any(), 20) }
        assertTrue(viewModel.contentResults.value.isEmpty())
    }

    @Test fun theNextAccountCanStartAndRecordItsOwnSearch() {
        val viewModel = model()
        switchTo("b")
        run()
        viewModel.submitSearch("otra consulta")
        run()
        assertEquals("otra consulta", viewModel.query.value)
        assertEquals(listOf(result), viewModel.contentResults.value)
        verify(exactly = 1) { history.write("b", listOf("otra consulta", "Guardada B")) }
        verify(exactly = 0) { history.write("a", any()) }
    }
}
