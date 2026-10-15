package conectate.com.util

import com.google.protobuf.Empty
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class ProtobufRuntimeSecurityTest {
    @Test
    fun deeplyNestedUnknownGroupsAreRejectedWithoutStackOverflow() {
        val depth = 2048
        val encoded = ByteArray(depth * 2) { index ->
            // Field 1, start group (wire type 3), followed by end group (wire type 4).
            if (index < depth) 11.toByte() else 12.toByte()
        }
        try {
            Empty.parseFrom(encoded)
            fail("Untrusted nested groups must exceed the parser recursion limit")
        } catch (_: InvalidProtocolBufferException) {
            // Expected controlled parse failure. A StackOverflowError fails this test.
        }
    }

    @Test
    fun normalUnknownFieldsRemainCompatible() {
        val encoded = byteArrayOf(8, 1)
        assertArrayEquals(encoded, Empty.parseFrom(encoded).toByteArray())
    }
}
