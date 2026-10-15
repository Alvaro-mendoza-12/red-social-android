package conectate.com.presentation.posts
import conectate.com.data.model.PostReactionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PostReactionUsersLoaderTest {
    private val person=PostReactionUser("a","Persona",reactionType="LIKE")
    @Test fun pendingRequestShowsLoadingAndNotAnEmptySuccess()=runTest {
        val pending=CompletableDeferred<Result<List<PostReactionUser>>>()
        val l=PostReactionUsersLoader(backgroundScope,{"owner"},{_,_->pending.await()})
        l.load("post");runCurrent();assertTrue(l.state.value.loading)
        pending.complete(Result.success(listOf(person)));runCurrent();assertEquals(listOf(person),l.state.value.users);assertFalse(l.state.value.loading)
    }
    @Test fun failedListHasRetryAndThenLoadsPeople()=runTest {
        var failed=true;val l=PostReactionUsersLoader(backgroundScope,{"owner"},{_,_->if(failed)Result.failure(Exception())else Result.success(listOf(person))})
        l.load("post");runCurrent();assertNotNull(l.state.value.error);failed=false;l.load("post");runCurrent();assertNull(l.state.value.error);assertEquals(1,l.state.value.users.size)
    }
    @Test fun accountSwitchCannotExposeThePreviousResult()=runTest {
        var owner="first";val pending=CompletableDeferred<Result<List<PostReactionUser>>>()
        val l=PostReactionUsersLoader(backgroundScope,{owner},{_,_->pending.await()});l.load("post");runCurrent();owner="second"
        pending.complete(Result.success(listOf(person)));runCurrent();assertTrue(l.state.value.users.isEmpty());assertFalse(l.state.value.loading)
    }
    @Test fun closingSheetCancelsAndClearsIt()=runTest {
        val l=PostReactionUsersLoader(backgroundScope,{"owner"},{_,_->Result.success(listOf(person))});l.load("post");runCurrent();l.clear();assertEquals(PostReactionUsersState(),l.state.value)
    }
    @Test fun duplicateRowsDoNotAddDuplicatePeople()=runTest {
        val l=PostReactionUsersLoader(backgroundScope,{"owner"},{_,_->Result.success(listOf(person,person))});l.load("post");runCurrent();assertEquals(1,l.state.value.users.size)
    }
}
