package conectate.com.data.repository.message

import conectate.com.test.UnitTestApplication
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Repeat the ownership/transaction scenarios on AOSP native SQLite as well. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationReadDeliveryNativeRoomTest : ConversationReadDeliveryRoomTest()
