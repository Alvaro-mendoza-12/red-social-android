package conectate.com.data.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationSchemaContractTest {

    @Test
    fun latestNormalizerExactlyMatchesAuthoritativeRoomSchema() {
        val schemaFile = schemaCandidates().firstOrNull(File::isFile)
        assertTrue(
            "Room schema $APP_DATABASE_VERSION was not exported under app/schemas",
            schemaFile != null
        )

        val database = Json.parseToJsonElement(schemaFile!!.readText()).jsonObject
            .getValue("database")
            .jsonObject
        assertEquals(
            "The exported Room schema version must match APP_DATABASE_VERSION",
            APP_DATABASE_VERSION,
            database.getValue("version").jsonPrimitive.content.toInt()
        )

        val authoritative = database.getValue("entities").jsonArray.associate { rawEntity ->
            val entity = rawEntity.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            val createSql = entity.getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", tableName)
            // Newer exports omit empty arrays. Non-empty indices are still compared exactly.
            val indices = entity["indices"]?.jsonArray.orEmpty().map { rawIndex ->
                rawIndex.jsonObject.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", tableName)
            }.sorted()
            tableName to MigrationSchemaPlanSnapshot(tableName, createSql, indices)
        }
        val normalizer = LatestSchemaNormalizer.schemaPlansForVerification()
            .associateBy(MigrationSchemaPlanSnapshot::tableName)

        assertEquals(
            "The migration normalizer must cover every Room table and no obsolete table",
            authoritative.keys.sorted(),
            normalizer.keys.sorted()
        )
        authoritative.forEach { (tableName, expected) ->
            val actual = requireNotNull(normalizer[tableName])
            assertEquals("CREATE TABLE drift for $tableName", expected.createSql, actual.createSql)
            assertEquals(
                "Index drift for $tableName",
                expected.indexSql,
                actual.indexSql.sorted()
            )
        }
    }

    private fun schemaCandidates(): List<File> = listOf(
        File("schemas/conectate.com.data.local.AppDatabase/$APP_DATABASE_VERSION.json"),
        File("app/schemas/conectate.com.data.local.AppDatabase/$APP_DATABASE_VERSION.json")
    )
}
