package conectate.com.core.media

import conectate.com.data.localfallback.UbuntuStorageUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StorageReadPolicyTest {
    private val backend = "https://api.example.test"
    private val path = "/storage/v1/object/public/chat-media/user/photo.jpg"
    @After fun reset() { StorageReadSession.install { null } }

    @Test fun exactOriginAndAllThreeReadRoutesAreAccepted() {
        for (route in listOf("public/", "sign/", ""))
            assertTrue(StorageReadPolicy.accepts("$backend/storage/v1/object/${route}chat-media/user/file.jpg", backend))
    }
    @Test fun port443IsEquivalentToHttpsDefault() {
        assertTrue(StorageReadPolicy.accepts("$backend:443$path", backend))
        assertFalse(StorageReadPolicy.accepts("$backend:444$path", backend))
    }
    @Test fun uppercaseHostRetainsTheSameAuthority() {
        assertTrue(StorageReadPolicy.accepts("https://API.EXAMPLE.TEST$path", backend))
    }
    @Test fun prefixSuffixAndSiblingHostsAreRejected() {
        for (host in listOf("api.example.test.attacker.test", "evil-api.example.test", "cdn.example.test", "api.example.test."))
            assertFalse(StorageReadPolicy.accepts("https://$host$path", backend))
    }
    @Test fun credentialsAndPercentEncodedAuthorityAreRejected() {
        assertFalse(StorageReadPolicy.accepts("https://name:secret@api.example.test$path", backend))
        assertFalse(StorageReadPolicy.accepts("https://api%2eexample.test$path", backend))
    }
    @Test fun unicodeHomographAndIdnaAlternativeCannotBorrowCredentials() {
        assertFalse(StorageReadPolicy.accepts("https://аpi.example.test$path", backend))
        assertFalse(StorageReadPolicy.accepts("https://xn--pi-6kc.example.test$path", backend))
    }
    @Test fun httpAndProtocolRelativeAndOpaqueLocationsAreRejected() {
        for (url in listOf("http://api.example.test$path", "//api.example.test$path", "file:$path", "data:text/plain,hi"))
            assertFalse(StorageReadPolicy.accepts(url, backend))
        assertFalse(StorageReadPolicy.accepts("$backend$path", "http://api.example.test"))
    }
    @Test fun queryCannotMasqueradeAsAnApprovedRoute() {
        assertFalse(StorageReadPolicy.accepts("$backend/redirect?next=$path", backend))
        assertFalse(StorageReadPolicy.accepts("$backend/other$path", backend))
    }
    @Test fun emptyObjectAndEncodedTraversalAndSeparatorsAreRejected() {
        for (suffix in listOf("", "/", "/../file.jpg", "/%2e%2e/file.jpg", "/x%2Fy.jpg", "/x%5cy.jpg", "/%00file.jpg"))
            assertFalse(StorageReadPolicy.accepts("$backend/storage/v1/object/public/chat-media$suffix", backend))
    }
    @Test fun escapedFilenameAndSignedQueryRemainAccepted() {
        assertTrue(StorageReadPolicy.accepts("$backend/storage/v1/object/sign/chat-media/u/foto%20uno%2B.jpg?token=fake.signature&width=384", backend))
    }
    @Test fun malformedValuesNeverThrow() {
        for (url in listOf(null, "", "\n", "https://[", "$backend/storage/v1/object/public/bucket/u/%XX"))
            assertFalse(StorageReadPolicy.accepts(url, backend))
    }
    @Test fun configuredApiPathIsMatchedAsAWhole() {
        assertTrue(StorageReadPolicy.accepts("$backend/api$path", "$backend/api/"))
        assertFalse(StorageReadPolicy.accepts("$backend/apix$path", "$backend/api"))
    }
    @Test fun generationChangeAndAccountChangeInvalidateEvenBufferedInput() {
        var current = credential("user-a", 1)
        StorageReadSession.install { current }
        val url = UbuntuStorageUrl.baseUrl+path
        val input = StorageReadSession.guardInputStream(url, byteArrayOf(1, 2, 3).inputStream(), current.identity)
        assertEquals(1, input.read())
        current = credential("user-a", 2)
        assertThrows(IOException::class.java) { input.read() }
        assertThrows(IOException::class.java) { StorageReadSession.requireCurrent(StorageReadIdentity("user-b", 2)) }
    }
    @Test fun tokenRenewalPreservesCurrentAccountIdentity() {
        var current = credential("user-a", 1)
        StorageReadSession.install { current }
        val identity = current.identity
        current = StorageReadCredential(identity, "renewed.jwt")
        StorageReadSession.requireCurrent(identity)
    }
    @Test fun logoutInvalidatesOpenReads() {
        StorageReadSession.install { credential("user-a", 1) }
        val identity = StorageReadSession.snapshot()?.identity
        StorageReadSession.install { null }
        assertThrows(IOException::class.java) { StorageReadSession.requireCurrent(identity) }
    }
    @Test fun cacheScopeSeparatesAccountsAndLoginGenerationsWithoutToken() {
        var current = credential("user-a", 1)
        StorageReadSession.install { current }
        val url = UbuntuStorageUrl.baseUrl+path
        val a = StorageReadSession.scopedCacheKey(url, "object")
        current = credential("user-b", 1)
        assertNotEquals(a, StorageReadSession.scopedCacheKey(url, "object"))
        current = credential("user-a", 2)
        assertNotEquals(a, StorageReadSession.scopedCacheKey(url, "object"))
        assertFalse(a.contains("synthetic.jwt"))
        assertFalse(current.toString().contains("synthetic.jwt"))
    }
    @Test fun externalCacheAndStreamsRemainUnchanged() {
        StorageReadSession.install { credential("user-a", 1) }
        val input = byteArrayOf(1).inputStream()
        assertSame(input, StorageReadSession.guardInputStream("https://external.test/photo.jpg", input, null))
        assertEquals("external-key", StorageReadSession.scopedCacheKey("https://external.test/photo.jpg", "external-key"))
    }
    @Test fun invalidTokensAndProviderFailureCannotBecomeAuthorization() {
        StorageReadSession.install { StorageReadCredential(StorageReadIdentity("a", 1), "fake\r\nInjected") }
        assertNull(StorageReadSession.snapshot())
        StorageReadSession.install { error("Synthetic provider failure") }
        assertNull(StorageReadSession.snapshot())
    }
    @Test fun readinessCanRenewTokenWithoutChangingIdentity() {
        var current = credential("a", 1)
        StorageReadSession.install(credentials = { current }, prepare = { expected ->
            current = StorageReadCredential(expected, "renewed.jwt")
            current
        })
        assertEquals("renewed.jwt", StorageReadSession.readySnapshot()!!.accessToken)
    }
    @Test fun readinessCannotReturnAnotherAccountsSession() {
        var current = credential("a", 1)
        StorageReadSession.install(credentials = { current }, prepare = {
            current = credential("b", 1)
            current
        })
        assertThrows(IOException::class.java) { StorageReadSession.readySnapshot() }
    }
    @Test fun preparationFailureDoesNotDowngradeAnAuthenticatedReadToAnonymous() {
        StorageReadSession.install(credentials = { credential("a", 1) }, prepare = { error("Synthetic refresh failure") })
        assertThrows(IOException::class.java) { StorageReadSession.readySnapshot() }
    }
    @Test fun anonymousPublicReadDoesNotRunSessionPreparation() {
        var attempts = 0
        StorageReadSession.install(credentials = { null }, prepare = { attempts++; credential("a", 1) })
        assertNull(StorageReadSession.readySnapshot())
        assertEquals(0, attempts)
    }
    @Test fun accountChangeInsideSingleByteReadDoesNotReturnTheByte() {
        var current: StorageReadCredential? = credential("a", 1)
        StorageReadSession.install { current }
        val input = object : java.io.InputStream() {
            override fun read(): Int { current = null; return 42 }
        }
        val guarded = StorageReadSession.guardInputStream(UbuntuStorageUrl.baseUrl+path, input, current!!.identity)
        assertThrows(IOException::class.java) { guarded.read() }
    }
    @Test fun accountChangeInsideBulkReadLeavesCallerBufferUntouched() {
        var current: StorageReadCredential? = credential("a", 1)
        StorageReadSession.install { current }
        val input = object : java.io.InputStream() {
            override fun read(): Int = 42
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = 42; current = null; return 1
            }
        }
        val guarded = StorageReadSession.guardInputStream(UbuntuStorageUrl.baseUrl+path, input, current!!.identity)
        val caller = byteArrayOf(7, 8, 9)
        assertThrows(IOException::class.java) { guarded.read(caller, 1, 1) }
        assertArrayEquals(byteArrayOf(7, 8, 9), caller)
    }
    @Test fun accountChangeInsideSkipDoesNotReturnProgress() {
        var current: StorageReadCredential? = credential("a", 1)
        StorageReadSession.install { current }
        val input = object : java.io.InputStream() {
            override fun read(): Int = 42
            override fun skip(count: Long): Long { current = null; return count }
        }
        val guarded = StorageReadSession.guardInputStream(UbuntuStorageUrl.baseUrl+path, input, current!!.identity)
        assertThrows(IOException::class.java) { guarded.skip(1) }
    }
    @Test fun bulkReadRemainsBoundedAndValidatesOffsets() {
        StorageReadSession.install { credential("a", 1) }
        val guarded = StorageReadSession.guardInputStream(UbuntuStorageUrl.baseUrl+path, ByteArray(128 * 1024) { 3 }.inputStream(), StorageReadSession.snapshot()!!.identity)
        assertEquals(0, guarded.read(ByteArray(1), 0, 0))
        assertThrows(IndexOutOfBoundsException::class.java) { guarded.read(ByteArray(1), 1, 1) }
        assertEquals(64 * 1024, guarded.read(ByteArray(128 * 1024)))
    }
    private fun credential(account: String, generation: Long) = StorageReadCredential(StorageReadIdentity(account, generation), "synthetic.jwt")
}
