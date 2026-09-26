package conectate.com.data.repository.message

import conectate.com.test.UnitTestApplication
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Same assertions on AOSP native SQLite, in addition to Windows' legacy test engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class InboxPreviewNativeReaderTest : InboxPreviewReaderTest()
