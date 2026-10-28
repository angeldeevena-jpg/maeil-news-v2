package com.maeilnews.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home-screen widget, as on the design board "widget home" (390 x 450).
 * One article at a time for the chosen region (KR, IN or World): the headline in both
 * languages, two audio buttons, previous and next, the weekly quiz download, and the word bank.
 * The word bank opens inside the widget itself (in place of the card), with 3 words per page.
 */
class NewsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
        fetchInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NEXT -> { move(context, +1); refreshAll(context) }
            ACTION_PREV -> { move(context, -1); refreshAll(context) }
            ACTION_REGION -> {
                setRegion(context, intent.getStringExtra(EXTRA_REGION) ?: REGIONS[0])
                refreshAll(context)
            }
            ACTION_WORD_BANK -> { toggleWordBank(context); refreshAll(context) }
            ACTION_REFRESH -> fetchInBackground(context)
            ACTION_SAVE -> saveCurrentArticle(context)
            ACTION_SAVE_QUIZ -> saveQuiz(context)
            ACTION_SPEAK_EN -> currentCard(context)?.let { Speaker.speak(context, it.englishTitle, Locale.US, 1.0f) }
            ACTION_SPEAK_KO -> currentCard(context)?.let { Speaker.speak(context, it.koreanTitle, Locale.KOREAN, 0.7f) }
            ACTION_SPEAK_WORD -> {
                val index = intent.getStringExtra(EXTRA_WORD)?.toIntOrNull()
                val word = currentCard(context)?.titleWords?.getOrNull(index ?: -1)
                if (word != null) Speaker.speak(context, word.ko, Locale.KOREAN, 0.5f)
            }
        }
    }

    private fun fetchInBackground(context: Context) {
        val pending = goAsync()
        executor.execute {
            try {
                NewsStore.refresh(context)
            } finally {
                refreshAll(context)
                pending.finish()
            }
        }
    }

    private fun saveCurrentArticle(context: Context) {
        val pending = goAsync()
        executor.execute {
            try {
                val card = currentCard(context)
                when {
                    card == null -> Downloader.toast(context, "No article loaded yet")
                    !card.saveAllowed -> Downloader.toast(context, "Save is for Korean articles only")
                    else -> {
                        val n = Downloader.saveArticle(context, card)
                        Downloader.toast(context, if (n > 0) "Saved $n files to your gallery" else "Nothing to save yet")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun saveQuiz(context: Context) {
        val pending = goAsync()
        executor.execute {
            try {
                val quiz = NewsStore.loadQuiz(context)
                if (quiz == null) {
                    Downloader.toast(context, "No quiz loaded yet")
                } else {
                    val n = Downloader.saveQuiz(context, quiz)
                    Downloader.toast(context, if (n > 0) "Saved $n quiz files to your gallery" else "Quiz files not ready yet")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PREFS = "widget"
        const val KEY_REGION = "region"
        const val KEY_WORD_BANK = "word_bank_open"
        const val KEY_WORD_PAGE = "word_page"
        const val EXTRA_REGION = "region"
        const val EXTRA_WORD = "word"
        val REGIONS = listOf("korean", "indian", "global")
        private const val WORDS_PER_PAGE = 3

        const val ACTION_PREV = "com.maeilnews.app.PREV"
        const val ACTION_NEXT = "com.maeilnews.app.NEXT"
        const val ACTION_REFRESH = "com.maeilnews.app.REFRESH"
        const val ACTION_SAVE = "com.maeilnews.app.SAVE"
        const val ACTION_SAVE_QUIZ = "com.maeilnews.app.SAVE_QUIZ"
        const val ACTION_REGION = "com.maeilnews.app.REGION"
        const val ACTION_WORD_BANK = "com.maeilnews.app.WORD_BANK"
        const val ACTION_SPEAK_EN = "com.maeilnews.app.SPEAK_EN"
        const val ACTION_SPEAK_KO = "com.maeilnews.app.SPEAK_KO"
        const val ACTION_SPEAK_WORD = "com.maeilnews.app.SPEAK_WORD"

        private val executor = Executors.newSingleThreadExecutor()
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun currentRegion(context: Context): String =
            prefs(context).getString(KEY_REGION, REGIONS[0]) ?: REGIONS[0]

        private fun setRegion(context: Context, region: String) {
            prefs(context).edit()
                .putString(KEY_REGION, region)
                .putBoolean(KEY_WORD_BANK, false)
                .putInt(KEY_WORD_PAGE, 0)
                .apply()
        }

        private fun indexKey(region: String) = "index_$region"

        fun currentIndex(context: Context): Int =
            prefs(context).getInt(indexKey(currentRegion(context)), 0)

        /** The articles of the region chosen in the widget, in the order they were collected. */
        fun regionCards(context: Context): List<Card> {
            val region = currentRegion(context)
            return NewsStore.loadNews(context)?.cards?.filter { it.region == region } ?: emptyList()
        }

        fun currentCard(context: Context): Card? {
            val cards = regionCards(context)
            if (cards.isEmpty()) return null
            return cards[wrap(currentIndex(context), cards.size)]
        }

        private fun wrap(index: Int, size: Int): Int = ((index % size) + size) % size

        private fun wordBankOpen(context: Context): Boolean =
            prefs(context).getBoolean(KEY_WORD_BANK, false)

        /** The arrows turn the article, or the word page while the word bank is open. */
        private fun move(context: Context, direction: Int) {
            if (wordBankOpen(context)) {
                val pages = wordPages(currentCard(context))
                if (pages <= 0) return
                val next = wrap(wordPage(context) + direction, pages)
                prefs(context).edit().putInt(KEY_WORD_PAGE, next).apply()
            } else {
                val count = regionCards(context).size
                if (count == 0) return
                val next = wrap(currentIndex(context) + direction, count)
                prefs(context).edit()
                    .putInt(indexKey(currentRegion(context)), next)
                    .putInt(KEY_WORD_PAGE, 0)
                    .apply()
            }
        }

        private fun toggleWordBank(context: Context) {
            prefs(context).edit()
                .putBoolean(KEY_WORD_BANK, !wordBankOpen(context))
                .putInt(KEY_WORD_PAGE, 0)
                .apply()
        }

        private fun wordPage(context: Context): Int = prefs(context).getInt(KEY_WORD_PAGE, 0)

        private fun wordPages(card: Card?): Int {
            val n = card?.titleWords?.size ?: 0
            return if (n == 0) 1 else (n + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NewsWidgetProvider::class.java))
            ids.forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_news)
            val region = currentRegion(context)
            val news = NewsStore.loadNews(context)
            val cards = regionCards(context)
            val card = currentCard(context)
            val inWords = wordBankOpen(context) && card != null

            views.setTextViewText(R.id.date, dateLabel(news?.date))

            regionChip(context, views, R.id.chip_korean, "korean", "KR", region)
            regionChip(context, views, R.id.chip_indian, "indian", "IN", region)
            regionChip(context, views, R.id.chip_global, "global", "World", region)

            // Body: article card or word bank panel
            views.setViewVisibility(R.id.card, if (inWords) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.word_panel, if (inWords) View.VISIBLE else View.GONE)

            if (card != null) {
                val position = wrap(currentIndex(context), cards.size) + 1
                views.setTextViewText(R.id.category, "${card.category} · $position / ${cards.size}")
                views.setTextViewText(R.id.headline, card.englishTitle)
                views.setTextViewText(R.id.headline_kr, card.koreanTitle)
                views.setViewVisibility(R.id.save, if (card.saveAllowed) View.VISIBLE else View.GONE)
                views.setOnClickPendingIntent(R.id.card, activity(context, DetailActivity::class.java, card.id))
                renderWords(context, views, card)
            } else {
                views.setTextViewText(R.id.category, "Maeil News")
                views.setTextViewText(R.id.headline, "Tap Maeil News above to load today's news")
                views.setTextViewText(R.id.headline_kr, "")
                views.setViewVisibility(R.id.save, View.GONE)
                views.setOnClickPendingIntent(R.id.card, broadcast(context, ACTION_REFRESH))
            }

            // Bottom bar: the word bank bar switches the body between article and words
            views.setTextViewText(
                R.id.word_bank_label,
                if (inWords) "← Back to article" else "Word bank · 단어장"
            )
            views.setTextViewText(R.id.word_bank_arrow, if (inWords) "" else "→")
            views.setOnClickPendingIntent(R.id.word_bank, broadcast(context, ACTION_WORD_BANK))

            // Word bank open: the next arrow turns yellow, as on the design board
            views.setInt(R.id.next, "setBackgroundResource", if (inWords) R.drawable.circle_yellow else R.drawable.circle_dark)
            views.setTextColor(R.id.next, if (inWords) Color.parseColor("#FF1F1A17") else Color.parseColor("#FFFFF8EE"))

            views.setOnClickPendingIntent(R.id.header_left, broadcast(context, ACTION_REFRESH))
            views.setOnClickPendingIntent(R.id.prev, broadcast(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.next, broadcast(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.save, broadcast(context, ACTION_SAVE))
            views.setOnClickPendingIntent(R.id.save_quiz, broadcast(context, ACTION_SAVE_QUIZ))
            views.setOnClickPendingIntent(R.id.audio_en, broadcast(context, ACTION_SPEAK_EN))
            views.setOnClickPendingIntent(R.id.audio_kr, broadcast(context, ACTION_SPEAK_KO))

            manager.updateAppWidget(widgetId, views)
        }

        /** Fills the word bank panel with the current page of the article's title words. */
        private fun renderWords(context: Context, views: RemoteViews, card: Card) {
            val words = card.titleWords
            val pages = wordPages(card)
            val page = wrap(wordPage(context), pages)
            val start = page * WORDS_PER_PAGE
            val end = minOf(words.size, start + WORDS_PER_PAGE)

            views.setTextViewText(R.id.word_page, "${page + 1} / $pages")
            views.setTextViewText(R.id.word_kr_title, card.koreanTitle)
            views.setTextViewText(
                R.id.word_range,
                if (words.isEmpty()) "No word list for this article." else "Words ${start + 1}–$end of ${words.size}"
            )

            val rows = listOf(
                intArrayOf(R.id.word_row_0, R.id.word_ko_0, R.id.word_roman_0, R.id.word_en_0, R.id.word_play_0),
                intArrayOf(R.id.word_row_1, R.id.word_ko_1, R.id.word_roman_1, R.id.word_en_1, R.id.word_play_1),
                intArrayOf(R.id.word_row_2, R.id.word_ko_2, R.id.word_roman_2, R.id.word_en_2, R.id.word_play_2)
            )
            rows.forEachIndexed { slot, ids ->
                val index = start + slot
                val word = words.getOrNull(index)
                views.setViewVisibility(ids[0], if (word != null) View.VISIBLE else View.GONE)
                if (word != null) {
                    views.setTextViewText(ids[1], word.ko)
                    views.setTextViewText(ids[2], word.roman)
                    views.setTextViewText(ids[3], word.en)
                    views.setOnClickPendingIntent(ids[4], broadcast(context, ACTION_SPEAK_WORD, EXTRA_WORD, index.toString()))
                }
            }
        }

        /** A region chip: yellow with dark text when chosen, plain cream text otherwise. */
        private fun regionChip(
            context: Context,
            views: RemoteViews,
            viewId: Int,
            key: String,
            label: String,
            selected: String
        ) {
            val chosen = key == selected
            views.setTextViewText(viewId, label)
            views.setInt(viewId, "setBackgroundResource", if (chosen) R.drawable.pill_yellow else 0)
            views.setTextColor(viewId, if (chosen) Color.parseColor("#FF1F1A17") else Color.parseColor("#FFFFF8EE"))
            views.setOnClickPendingIntent(viewId, broadcast(context, ACTION_REGION, EXTRA_REGION, key))
        }

        /** "2026-10-27" becomes "매일 뉴스 · 10월 27일". */
        private fun dateLabel(date: String?): String {
            val parts = date?.split("-") ?: return "매일 뉴스"
            if (parts.size != 3) return "매일 뉴스"
            val month = parts[1].toIntOrNull() ?: return "매일 뉴스"
            val day = parts[2].toIntOrNull() ?: return "매일 뉴스"
            return "매일 뉴스 · ${month}월 ${day}일"
        }

        private fun broadcast(
            context: Context,
            action: String,
            extraKey: String? = null,
            extraValue: String? = null
        ): PendingIntent {
            val intent = Intent(context, NewsWidgetProvider::class.java).setAction(action)
            if (extraKey != null) intent.putExtra(extraKey, extraValue)
            return PendingIntent.getBroadcast(context, (action + extraValue).hashCode(), intent, FLAGS)
        }

        private fun activity(context: Context, target: Class<*>, cardId: String): PendingIntent {
            val intent = Intent(context, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DetailActivity.EXTRA_ID, cardId)
            return PendingIntent.getActivity(context, (target.name + cardId).hashCode(), intent, FLAGS)
        }
    }
}
