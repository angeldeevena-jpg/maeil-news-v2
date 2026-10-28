package com.maeilnews.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ---------- data, as written by generate.mjs and render.mjs ----------

data class Word(val ko: String, val en: String, val roman: String, val note: String)

data class SummaryItem(val en: String, val ko: String, val words: List<Word>)

data class Media(val video: String?, val cards: List<String>)

data class Card(
    val id: String,
    val region: String,
    val category: String,
    val source: String,
    val link: String,
    val englishTitle: String,
    val koreanTitle: String,
    val titleWords: List<Word>,
    val summary: List<SummaryItem>,
    val saveAllowed: Boolean,
    val media: Media?,
    /** One complete news sentence giving the gist, in English and Korean (empty for older articles). */
    val gistEn: String = "",
    val gistKo: String = ""
)

data class NewsSet(val date: String, val note: String, val cards: List<Card>)

data class QuizQuestion(
    val level: String,
    val bonus: Boolean,
    val category: String,
    val questionEn: String,
    val questionKo: String,
    val options: List<Pair<String, String>>,
    val answer: Int,
    val whyEn: String,
    val whyKo: String
)

data class Quiz(val week: String, val questions: List<QuizQuestion>, val media: Media?)

fun regionLabel(region: String): String = when (region) {
    "korean" -> "Korean news"
    "indian" -> "Indian news"
    "global" -> "Global news"
    else -> region
}

/**
 * Downloads the daily files from the public site, keeps a copy on the phone,
 * and reads that copy. The widget and the app both read this copy, so they work offline.
 */
object NewsStore {
    private const val NEWS_FILE = "news.json"
    private const val QUIZ_FILE = "quiz.json"

    /** Downloads news.json and quiz.json. Call on a background thread. Returns false if the news failed. */
    fun refresh(context: Context): Boolean {
        return try {
            download(BuildConfig.CONTENT_BASE_URL + NEWS_FILE, File(context.filesDir, NEWS_FILE))
            runCatching { download(BuildConfig.CONTENT_BASE_URL + QUIZ_FILE, File(context.filesDir, QUIZ_FILE)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun download(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            target.writeText(text)
        } finally {
            conn.disconnect()
        }
    }

    fun loadNews(context: Context): NewsSet? {
        val file = File(context.filesDir, NEWS_FILE)
        if (!file.exists()) return null
        return runCatching { parseNews(file.readText()) }.getOrNull()
    }

    fun loadQuiz(context: Context): Quiz? {
        val file = File(context.filesDir, QUIZ_FILE)
        if (!file.exists()) return null
        return runCatching { parseQuiz(file.readText()) }.getOrNull()
    }

    fun parseNews(raw: String): NewsSet {
        val root = JSONObject(raw)
        val array = root.optJSONArray("cards") ?: JSONArray()
        val cards = (0 until array.length()).map { parseCard(array.getJSONObject(it)) }
        return NewsSet(root.optString("date"), root.optString("note"), cards)
    }

    private fun parseCard(o: JSONObject): Card {
        val summaryArray = o.optJSONArray("summary")
        val summary = if (summaryArray == null) emptyList() else
            (0 until summaryArray.length()).map { i ->
                val s = summaryArray.getJSONObject(i)
                SummaryItem(s.optString("en"), s.optString("ko"), parseWords(s.optJSONArray("words")))
            }
        return Card(
            id = o.optString("id"),
            region = o.optString("region"),
            category = o.optString("category"),
            source = o.optString("source"),
            link = o.optString("link"),
            englishTitle = o.optString("english_title"),
            koreanTitle = o.optString("korean_title"),
            titleWords = parseWords(o.optJSONArray("title_words")),
            summary = summary,
            saveAllowed = o.optBoolean("save_allowed", false),
            media = parseMedia(o.optJSONObject("media")),
            gistEn = o.optString("gist_en"),
            gistKo = o.optString("gist_ko")
        )
    }

    fun parseQuiz(raw: String): Quiz {
        val root = JSONObject(raw)
        val array = root.optJSONArray("questions") ?: JSONArray()
        val questions = (0 until array.length()).map { i ->
            val q = array.getJSONObject(i)
            val options = q.optJSONArray("options") ?: JSONArray()
            QuizQuestion(
                level = q.optString("level_label"),
                bonus = q.optBoolean("bonus", false),
                category = q.optString("category"),
                questionEn = q.optString("question_en"),
                questionKo = q.optString("question_ko"),
                options = (0 until options.length()).map { j ->
                    val o = options.getJSONObject(j)
                    Pair(o.optString("en"), o.optString("kr").ifBlank { o.optString("ko") })
                },
                answer = q.optInt("answer", 0),
                whyEn = q.optString("why_en"),
                whyKo = q.optString("why_kr").ifBlank { q.optString("why_ko") }
            )
        }
        return Quiz(root.optString("week"), questions, parseMedia(root.optJSONObject("media")))
    }

    private fun parseWords(array: JSONArray?): List<Word> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val w = array.getJSONObject(i)
            Word(w.optString("ko"), w.optString("en"), w.optString("roman"), w.optString("note"))
        }
    }

    private fun parseMedia(o: JSONObject?): Media? {
        if (o == null) return null
        val cards = o.optJSONArray("cards")
        val list = if (cards == null) emptyList() else (0 until cards.length()).map { cards.getString(it) }
        return Media(o.optString("video").ifBlank { null }, list)
    }
}
