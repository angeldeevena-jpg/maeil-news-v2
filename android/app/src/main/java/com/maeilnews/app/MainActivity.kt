package com.maeilnews.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app screen: refresh the news, a list of today's articles (tap to read, save if Korean),
 * and the weekly quiz download.
 */
class MainActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        column.removeAllViews()
        column.addView(line("Maeil News · 매일 뉴스", 20f, bold = true))

        column.addView(Button(this).apply {
            text = "⟳ Refresh news"
            setOnClickListener { refreshNews() }
        })

        val news = NewsStore.loadNews(this)
        if (news == null) {
            column.addView(line("No news on this phone yet. Tap Refresh.", 14f))
            return
        }
        column.addView(line("Date: ${news.date}   ${news.note}", 12f))

        news.cards.forEach { card ->
            column.addView(line("${card.category} · ${regionLabel(card.region)}", 12f, bold = true))
            column.addView(TextView(this).apply {
                text = card.englishTitle
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(2))
                setOnClickListener { read(card.id) }
            })
            column.addView(line(card.koreanTitle, 14f))
            column.addView(Button(this).apply {
                text = "Read"
                setOnClickListener { read(card.id) }
            })
            if (card.saveAllowed) {
                column.addView(Button(this).apply {
                    text = "⤓ Save cards + video"
                    setOnClickListener {
                        Thread {
                            val n = Downloader.saveArticle(this@MainActivity, card)
                            Downloader.toast(
                                this@MainActivity,
                                if (n > 0) "Saved $n files to your gallery" else "Nothing to save yet"
                            )
                        }.start()
                    }
                })
            }
        }

        NewsStore.loadQuiz(this)?.let { quiz ->
            column.addView(line("Weekly quiz · ${quiz.week}", 14f, bold = true))
            column.addView(Button(this).apply {
                text = "⤓ Weekly quiz: cards + video"
                setOnClickListener {
                    Thread {
                        val n = Downloader.saveQuiz(this@MainActivity, quiz)
                        Downloader.toast(
                            this@MainActivity,
                            if (n > 0) "Saved $n quiz files to your gallery" else "Quiz files not ready yet"
                        )
                    }.start()
                }
            })
        }
    }

    private fun refreshNews() {
        Thread {
            val ok = NewsStore.refresh(this)
            NewsWidgetProvider.refreshAll(this)
            Downloader.toast(this, if (ok) "News updated" else "Could not reach the news site")
            runOnUiThread { build() }
        }.start()
    }

    private fun read(cardId: String) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, cardId))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun line(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
}
