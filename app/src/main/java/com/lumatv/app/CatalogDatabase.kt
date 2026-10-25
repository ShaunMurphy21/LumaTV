package com.lumatv.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-disk Xtream VOD catalogue. Keep hundreds of thousands of titles out of
 * the Fire TV heap and do not store provider credentials or playable URLs.
 */
class CatalogDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lumatv_vod.db", null, 1) {

    init { setWriteAheadLoggingEnabled(true) } // Readers remain responsive during imports.

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE titles(
                id TEXT PRIMARY KEY, title TEXT NOT NULL, type TEXT NOT NULL,
                category_id TEXT NOT NULL, group_name TEXT NOT NULL,
                image TEXT, description TEXT, series_id TEXT,
                backdrop TEXT, stream_id TEXT, extension TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX title_type_group ON titles(type, group_name)")
        db.execSQL("CREATE INDEX title_type_name ON titles(type, title COLLATE NOCASE)")
        db.execSQL("""
            CREATE TABLE completed_categories(
                type TEXT NOT NULL, category_id TEXT NOT NULL,
                PRIMARY KEY(type, category_id)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS titles")
        db.execSQL("DROP TABLE IF EXISTS completed_categories")
        onCreate(db)
    }

    fun reset() {
        writableDatabase.delete("titles", null, null)
        writableDatabase.delete("completed_categories", null, null)
    }

    fun isComplete(type: ContentType, categoryId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM completed_categories WHERE type=? AND category_id=? LIMIT 1",
            arrayOf(type.name, categoryId)
        ).use { it.moveToFirst() }

    /**
     * One transaction and ONE compiled INSERT per category. Reusing the
     * prepared statement is substantially cheaper on a Fire TV than building
     * thousands of ContentValues objects and SQL statements.
     */
    fun storeCategory(category: SourceService.ProviderCategory, rows: List<MediaEntry>) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("titles", "type=? AND category_id=?", arrayOf(category.type.name, category.id))
            db.compileStatement("""
                INSERT OR REPLACE INTO titles
                (id,title,type,category_id,group_name,image,description,series_id,backdrop,stream_id,extension)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()).use { statement ->
                for (entry in rows) {
                    statement.clearBindings()
                    statement.bindString(1, entry.id)
                    statement.bindString(2, entry.title)
                    statement.bindString(3, entry.type.name)
                    statement.bindString(4, category.id)
                    statement.bindString(5, category.name)
                    statement.bindString(6, entry.image)
                    statement.bindString(7, entry.description)
                    statement.bindString(8, entry.seriesId)
                    statement.bindString(9, entry.backdrop)
                    if (entry.type == ContentType.MOVIE) {
                        // Retain the stream token only, never provider credentials.
                        val file = entry.url.substringBefore('?').substringAfterLast('/')
                        statement.bindString(10, file.substringBeforeLast('.', ""))
                        statement.bindString(11, file.substringAfterLast('.', "mp4"))
                    } else {
                        statement.bindNull(10)
                        statement.bindNull(11)
                    }
                    statement.executeInsert()
                }
            }
            ContentValues().apply {
                put("type", category.type.name)
                put("category_id", category.id)
            }.also {
                db.insertWithOnConflict(
                    "completed_categories", null, it, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(type: ContentType, query: String = "", category: String = "All"): Int {
        val (where, args) = selection(type, query, category)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM titles WHERE $where", args
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun page(provider: ProviderConfig, type: ContentType, query: String = "",
             category: String = "All", limit: Int = 180, offset: Int = 0): List<MediaEntry> {
        val (where, args) = selection(type, query, category)
        val result = ArrayList<MediaEntry>()
        val sql = """
            SELECT id,title,group_name,image,description,series_id,backdrop,stream_id,extension
            FROM titles WHERE $where ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, args + arrayOf(limit.coerceIn(1, 300).toString(),
            offset.coerceAtLeast(0).toString())).use { cur ->
            val base = provider.url.trimEnd('/')
            val username = Net.urlEncode(provider.username)
            val password = Net.urlEncode(provider.password)
            while (cur.moveToNext()) {
                val sid = cur.getString(7).orEmpty()
                val ext = cur.getString(8).orEmpty().ifBlank { "mp4" }
                val url = if (type == ContentType.MOVIE && sid.isNotBlank())
                    "$base/movie/$username/$password/$sid.$ext" else ""
                result.add(MediaEntry(
                    id = cur.getString(0), title = cur.getString(1),
                    type = type, url = url, group = cur.getString(2),
                    image = cur.getString(3).orEmpty(),
                    description = cur.getString(4).orEmpty(),
                    seriesId = cur.getString(5).orEmpty(),
                    backdrop = cur.getString(6).orEmpty()
                ))
            }
        }
        return result
    }

    /** All editions of a show stay in the database, including entries in late categories. */
    fun matchingSeries(provider: ProviderConfig, canonical: String): List<MediaEntry> {
        if (canonical.isBlank()) return emptyList()
        // Bounded SQL candidates: full catalogue never materialises in memory.
        val words = canonical.split(' ').filter(String::isNotBlank)
        val keyword = words.firstOrNull()?.take(40).orEmpty()
        return page(provider, ContentType.SERIES, keyword, "All", 300, 0)
            .filter { SeriesVariants.canonicalTitle(it.title) == canonical }
    }

    fun categoryNames(type: ContentType, max: Int = 80): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT group_name FROM titles WHERE type=? ORDER BY group_name LIMIT ?",
            arrayOf(type.name, max.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result
    }

    private fun selection(type: ContentType, query: String, category: String): Pair<String, Array<String>> {
        val clauses = mutableListOf("type=?")
        val args = mutableListOf(type.name)
        if (query.isNotBlank()) {
            clauses.add("(title LIKE ? ESCAPE '\\' OR group_name LIKE ? ESCAPE '\\')")
            val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args.add("%$escaped%")
            args.add("%$escaped%")
        }
        if (category != "All" && query.isBlank()) {
            clauses.add("group_name=?")
            args.add(category)
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }
}
