package io.github.aedev.flow.data.profile

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Every place the app keeps data is classified in `profiles/store_inventory.tsv` as DEVICE, PROFILE, CACHE or
 * DEFAULT_ONLY, so nothing can leak between profiles through a store nobody placed. A source scan rather than a
 * runtime registry, because it also catches a plain `File(context.filesDir, "x")`. A new store fails here until it
 * has a row, and a row whose code is gone fails too.
 */
class StoreScopeGuardTest {
    private data class Row(
        val kind: String,
        val name: String,
        val source: String,
        val scope: String,
        val storeClass: String,
        val reason: String,
    )

    private data class Hit(
        val kind: String,
        val source: String,
        val name: String?,
    )

    private class NamedCall(
        val kind: String,
        val start: Regex,
        val parameter: String,
        val position: Int,
    )

    private val rows: List<Row> by lazy {
        val text = checkNotNull(javaClass.getResource("/profiles/store_inventory.tsv")).readText()
        text
            .lines()
            .drop(1)
            .filter(String::isNotBlank)
            .map { line ->
                val cells = line.split('\t')
                check(cells.size == 6) { "Expected 6 columns: $line" }
                Row(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5])
            }
    }

    private val sources: Map<String, String> by lazy {
        FLAVORS
            .flatMap { flavor ->
                val root = File("src/$flavor/java/$PACKAGE_PATH")
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { "$flavor:${it.relativeTo(root).invariantSeparatorsPath}" to code(it) }
                    .toList()
            }.toMap()
    }

    private val hits: Set<Hit> by lazy {
        sources
            .flatMap { (source, text) ->
                NAMED_CALLS.flatMap { call ->
                    call.start.findAll(text).map { Hit(call.kind, source, nameOf(call, text, it.range.last)) }
                } +
                    UNNAMED_PATTERNS.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { (kind, _) -> Hit(kind, source, null) } +
                    DIRECTORY_ROOTS.filter { (_, root) -> usesRoot(text, root) }.map { (kind, _) -> Hit(kind, source, null) }
            }.toSet()
    }

    @Test
    fun `every row is well formed`() {
        val problems =
            rows.flatMap { row ->
                listOfNotNull(
                    "unknown kind".takeIf { row.kind !in KINDS },
                    "unknown scope".takeIf { row.scope !in SCOPES },
                    "PROFILE needs a class".takeIf { row.scope == "PROFILE" && row.storeClass !in PROFILE_CLASSES },
                    "only PROFILE has a class".takeIf { row.scope != "PROFILE" && row.storeClass != "-" },
                    "no reason".takeIf { row.reason.isBlank() },
                    "source missing".takeIf { row.source !in sources },
                ).map { "$it: ${row.kind} ${row.name} ${row.source}" }
            } +
                rows
                    .groupBy { Triple(it.kind, it.name, it.source) }
                    .filterValues { it.size > 1 }
                    .keys
                    .map { "duplicate: $it" }
        assertWithMessage("Malformed rows in store_inventory.tsv").that(problems).isEmpty()
    }

    @Test
    fun `every store in the code has a row`() {
        assertThat(hits).isNotEmpty()
        val unclassified = hits.filter { hit -> rows.none { row -> covers(row, hit) } }
        assertWithMessage("Add a row to store_inventory.tsv for each of these, with its profile scope")
            .that(unclassified)
            .isEmpty()
    }

    @Test
    fun `every row still points at code`() {
        val stale =
            rows
                .filter { it.kind in SCANNED_KINDS }
                .filter { row -> hits.none { hit -> hit.kind == row.kind && covers(row, hit) } }
        assertWithMessage("These rows no longer match any code; remove or correct them").that(stale).isEmpty()
    }

    @Test
    fun `every Room table has a row`() {
        val database = sources.getValue(APP_DATABASE)
        val entities =
            ENTITY_LIST
                .find(database)
                .let(::checkNotNull)
                .groupValues[1]
                .let { list -> Regex("""(\w+)::class""").findAll(list).map { it.groupValues[1] }.toList() }
        val tables = entities.map(::tableOf).toSet()
        assertThat(tables).hasSize(entities.size)
        assertWithMessage("Room tables vs room_table rows")
            .that(rows.filter { it.kind == "room_table" }.map { it.name to it.source }.toSet())
            .isEqualTo(tables)
    }

    @Test
    fun `every DAO has a row`() {
        val daos = DAO_ACCESSOR.findAll(sources.getValue(APP_DATABASE)).map { it.groupValues[1] }.toSet()
        assertThat(daos).isNotEmpty()
        assertWithMessage("AppDatabase DAOs vs room_dao rows")
            .that(rows.filter { it.kind == "room_dao" }.map { it.name }.toSet())
            .isEqualTo(daos)
    }

    private fun covers(
        row: Row,
        hit: Hit,
    ): Boolean {
        if (row.source != hit.source) return false
        if (hit.kind == "datastore_file" && row.kind in DATASTORE_KINDS) return true
        return row.kind == hit.kind && (hit.name == null || hit.name == row.name)
    }

    private fun tableOf(entity: String): Pair<String, String> {
        val (source, text) =
            sources.entries.single { (source, text) ->
                source.startsWith("main:data/local/entity/") && Regex("""\bclass\s+$entity\b""").containsMatchIn(text)
            }
        val declaration = Regex("""\bclass\s+$entity\b""").find(text)!!.range.first
        val annotation = text.lastIndexOf("@Entity(", declaration)
        val table =
            TABLE_NAME.find(text.substring(annotation, declaration))?.groupValues?.get(1) ?: entity
        return table to source
    }

    /** The store name a call passes, with a same-file `const val` resolved; a computed name stays as written. */
    private fun nameOf(
        call: NamedCall,
        text: String,
        openParen: Int,
    ): String {
        val arguments = arguments(text, openParen)
        val named = arguments.firstOrNull { it.startsWith("${call.parameter} ") || it.startsWith("${call.parameter}=") }
        val argument = (named?.substringAfter('=') ?: arguments.getOrElse(call.position) { "" }).trim()
        STRING_LITERAL.matchEntire(argument)?.let { return it.groupValues[1] }
        if (CONSTANT.matches(argument)) {
            Regex("""const\s+val\s+$argument\s*=\s*"([^"$]*)"""").find(text)?.let { return it.groupValues[1] }
        }
        return argument
    }

    private fun arguments(
        text: String,
        openParen: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var index = openParen + 1
        while (index < text.length) {
            val char = text[index]
            when {
                inString -> {
                    if (char == '\\') {
                        current.append(char)
                        index++
                        current.append(text[index])
                    } else {
                        if (char == '"') inString = false
                        current.append(char)
                    }
                }

                char == '"' -> {
                    inString = true
                    current.append(char)
                }

                char in "([{" -> {
                    depth++
                    current.append(char)
                }

                char in ")]}" && depth == 0 -> {
                    break
                }

                char in ")]}" -> {
                    depth--
                    current.append(char)
                }

                char == ',' && depth == 0 -> {
                    result += current.toString().trim()
                    current.clear()
                }

                else -> {
                    current.append(char)
                }
            }
            index++
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    /**
     * True when the file reads [root] from a context, or bare inside a Context subclass. A local or parameter
     * with the same name shadows the bare form.
     */
    private fun usesRoot(
        text: String,
        root: String,
    ): Boolean {
        if (Regex("""\.$root\b""").containsMatchIn(text)) return true
        val shadowed = Regex("""\b(?:val|var)\s+$root\b|\b$root\s*:""").containsMatchIn(text)
        return !shadowed && Regex("""(?<![\w.$])$root\b""").containsMatchIn(text)
    }

    private fun code(file: File): String =
        file
            .readLines()
            .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .joinToString("\n") { it.replace(TRAILING_COMMENT, "") }

    private companion object {
        val FLAVORS = listOf("main", "github", "foss")
        const val PACKAGE_PATH = "io/github/aedev/flow"
        const val APP_DATABASE = "main:data/local/AppDatabase.kt"

        val SCOPES = setOf("PROFILE", "DEVICE", "CACHE", "DEFAULT_ONLY")
        val PROFILE_CLASSES = setOf("settings", "library", "private", "identity")
        val DATASTORE_KINDS = setOf("prefs_datastore", "typed_datastore", "factory_datastore", "datastore_file")

        val NAMED_CALLS =
            listOf(
                NamedCall("prefs_datastore", Regex("""(?<!fun )\bsafePreferencesDataStore\("""), "name", 0),
                NamedCall("typed_datastore", Regex("""\bby\s+dataStore\("""), "fileName", 0),
                NamedCall("shared_prefs", Regex("""\bgetSharedPreferences\("""), "name", 0),
                NamedCall("room_database", Regex("""\bdatabaseBuilder\("""), "name", 2),
            )

        val UNNAMED_PATTERNS =
            mapOf(
                "datastore_wrapper" to Regex("""\bpreferencesDataStore\("""),
                "factory_datastore" to Regex("""\b(?:DataStoreFactory|PreferenceDataStoreFactory)\.create\b"""),
                "datastore_file" to Regex("""\b(?:dataStoreFile|preferencesDataStoreFile)\("""),
                "external_files_dir" to Regex("""\bgetExternalFilesDir\("""),
                "public_dir" to Regex("""\bgetExternalStoragePublicDirectory\("""),
                "sqlite_file" to Regex("""\bSQLiteDatabase\.open\w*\("""),
                "webview" to Regex("""\bWebView\("""),
                "glance_state" to Regex("""\bPreferencesGlanceStateDefinition\b"""),
            )

        val DIRECTORY_ROOTS =
            mapOf(
                "files_dir" to "filesDir",
                "no_backup_dir" to "noBackupFilesDir",
                "cache_dir" to "cacheDir",
            )

        val SCANNED_KINDS = NAMED_CALLS.map { it.kind }.toSet() + UNNAMED_PATTERNS.keys + DIRECTORY_ROOTS.keys
        val KINDS = SCANNED_KINDS + setOf("room_table", "room_dao")

        val TRAILING_COMMENT = Regex("""\s//\s.*$""")
        val STRING_LITERAL = Regex(""""([^"$]*)"""")
        val CONSTANT = Regex("""[A-Z][A-Z0-9_]*""")
        val ENTITY_LIST = Regex("""entities\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val TABLE_NAME = Regex("""tableName\s*=\s*"([^"]+)"""")
        val DAO_ACCESSOR = Regex("""abstract\s+fun\s+\w+\(\)\s*:\s*(\w+)""")
    }
}
