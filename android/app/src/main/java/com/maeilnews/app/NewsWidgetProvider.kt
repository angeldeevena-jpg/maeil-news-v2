package com.maeilnews.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

/**
 * Home-screen widget: one article at a time. It shows the English and Korean headline,
 * the word translations of the Korean title, previous and next, a "Read" button that opens the
 * summary popup, a refresh button, and save buttons for Korean articles and the weekly quiz.
 */
class NewsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
        fetchInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NEXT -> { step(context, +1); refreshAll(context) }
            ACTION_PREV -> { step(context, -1); refreshAll(context) }
            ACTION_REFRESH -> fetchInBackground(context)
            ACTION_SAVE -> saveCurrentArticle(context)
            ACTION_SAVE_QUIZ -> saveQuiz(context)
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
        const val KEY_INDEX = "index"
        const val ACTION_PREV = "com.maeilnews.app.PREV"
        const val ACTION_NEXT = "com.maeilnews.app.NEXT"
        const val ACTION_REFRESH = "com.maeilnews.app.REFRESH"
        const val ACTION_SAVE = "com.maeilnews.app.SAVE"
        const val ACTION_SAVE_QUIZ = "com.maeilnews.app.SAVE_QUIZ"

        private val executor = Executors.newSingleThreadExecutor()

        fun currentIndex(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INDEX, 0)

        fun currentCard(context: Context): Card? {
            val cards = NewsStore.loadNews(context)?.cards ?: return null
            return cards.getOrNull(currentIndex(context))
        }

        private fun step(context: Context, direction: Int) {
            val count = NewsStore.loadNews(context)?.cards?.size ?: return
            if (count == 0) return
            val next = ((currentIndex(context) + direction) % count + count) % count
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_INDEX, next).apply()
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NewsWidgetProvider::class.java))
            ids.forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_news)
            val card = currentCard(context)

            if (card != null) {
                views.setTextViewText(R.id.category, "${card.category} · ${regionLabel(card.region)}")
                views.setTextViewText(R.id.headline, card.englishTitle)
                views.setTextViewText(R.id.headline_kr, card.koreanTitle)
                views.setTextViewText(
                    R.id.word_bank,
                    card.titleWords.joinToString("   ") { "${it.ko} ${it.en}" }
                )
                views.setViewVisibility(R.id.save, if (card.saveAllowed) View.VISIBLE else View.GONE)
            } else {
                views.setTextViewText(R.id.category, "Maeil News")
                views.setTextViewText(R.id.headline, "Tap ⟳ to load today's news")
                views.setTextViewText(R.id.headline_kr, "")
                views.setTextViewText(R.id.word_bank, "")
                views.setViewVisibility(R.id.save, View.GONE)
            }

            views.setOnClickPendingIntent(R.id.prev, broadcast(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.next, broadcast(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.refresh, broadcast(context, ACTION_REFRESH))
            views.setOnClickPendingIntent(R.id.save, broadcast(context, ACTION_SAVE))
            views.setOnClickPendingIntent(R.id.save_quiz, broadcast(context, ACTION_SAVE_QUIZ))
            views.setOnClickPendingIntent(R.id.details, activity(context, DetailActivity::class.java))

            manager.updateAppWidget(widgetId, views)
        }

        private fun broadcast(context: Context, action: String): PendingIntent {
            val intent = Intent(context, NewsWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun activity(context: Context, target: Class<*>): PendingIntent {
            val intent = Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                target.name.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
