package conectate.com.smoke

import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in diagnostics: counts/state only, no content, tokens or identifiers. */
@RunWith(AndroidJUnit4::class)
class DeviceOutboxReadOnlyTest {
    @Test fun inspectOutboxWithoutChangingUserData() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOutboxDiagnostics")=="true")
        val context=instrumentation.targetContext
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val caps=manager.getNetworkCapabilities(manager.activeNetwork)
        val report=JSONObject().put("internet",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true)
            .put("validated",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
        val pending=JSONArray();val workers=JSONArray()
        context.databaseList().filterNot { it.endsWith("-wal")||it.endsWith("-shm") }.forEach { name ->
            runCatching { SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables=mutableSetOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use { c -> while(c.moveToNext())tables.add(c.getString(0)) }
                if("messages" in tables) db.rawQuery("SELECT content_type,status,COUNT(*),MIN(created_at),MAX(created_at) FROM messages WHERE is_synced=0 AND pending_action='send' GROUP BY content_type,status",null).use { c ->
                    while(c.moveToNext())pending.put(JSONObject().put("type",c.getString(0)).put("state",c.getString(1)).put("count",c.getInt(2)).put("first",c.getString(3)).put("last",c.getString(4)))
                }
                if("WorkSpec" in tables || "workspec" in tables) db.rawQuery("SELECT worker_class_name,state,run_attempt_count,COUNT(*) FROM workspec WHERE state IN (0,1,4) GROUP BY worker_class_name,state,run_attempt_count",null).use { c ->
                    while(c.moveToNext())workers.put(JSONObject().put("worker",c.getString(0)).put("state",c.getInt(1)).put("attempts",c.getInt(2)).put("count",c.getInt(3)))
                }
            } }.onFailure { report.put("readFailure",it.javaClass.simpleName) }
        }
        report.put("pending",pending).put("workers",workers)
        File(context.getExternalFilesDir(null),"qa-outbox-metadata.json").writeText(report.toString(2))
    }
}
