package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

/** Host-side Android simulation. Never connects to the owner's phone. */
@RunWith(RobolectricTestRunner::class)
class ChatMigrationTest {
    @Test fun versionSixUpgradeKeepsExistingOwnerAndMessages() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "chat-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.fsaint.androidagent.data.AgentDatabase/6.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO principals (id,e164,role,displayName,content) VALUES ('owner','+14155550100','OWNER',NULL,NULL)")
            db.execSQL("INSERT INTO conversation_messages VALUES ('old-message','legacy',1,X'6162')")
            db.version = 6
        }
        val db = AgentDatabaseTestFactory.open(context, name)
        try {
            assertEquals("owner", db.durableStateDao().owner()!!.id)
            assertContentEquals(byteArrayOf(97,98), db.durableStateDao().conversation("legacy").single().content)
            assertNotNull(ChatRepository(db).outside("owner"))
            assertEquals(7, db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
