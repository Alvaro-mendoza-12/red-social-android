package conectate.com.test

import android.app.Application

/** Avoids production Firebase/Hilt initialization for isolated JVM tests. */
class UnitTestApplication : Application()
