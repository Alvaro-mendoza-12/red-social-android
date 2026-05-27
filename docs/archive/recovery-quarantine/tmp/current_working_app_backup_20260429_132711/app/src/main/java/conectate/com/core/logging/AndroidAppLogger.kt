package conectate.com.core.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAppLogger @Inject constructor() : AppLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, throwable)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }
}

