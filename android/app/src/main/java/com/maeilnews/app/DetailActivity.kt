package com.maeilnews.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The popup behind the widget's "Read" button: the English and Korean headline, the words of the
 * Korean title, then the 5 English sentences, the same 5 in Korean, and each Korean word's meaning.
 */
class DetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cards = NewsStore.loadNews(this)?.cards ?: emptyList()
        val index = intent.getIntExtra(EXTRA_INDEX, NewsWidgetProvider.currentIndex(this))
        val card = cards.getOrNull(index)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)

        if (card == null) {
            column.addView(line("No article loaded. Go back and tap refresh.", 16f))
            return
        }

        column.addView(line("${card.category} · ${regionLabel(card.region)}", 12f, bold = true))
        column.addView(line(card.englishTitle, 22f, bold = true))
        column.addView(line(card.koreanTitle, 18f))

        column.addView(heading("Words in the title"))
        if (card.titleWords.isEmpty()) column.addView(line("No word list for this article.", 14f))
        card.titleWords.forEach { w ->
            column.addView(line("${w.ko}   ${w.roman}   —   ${w.en}", 15f))
        }

        column.addView(heading("Summary"))
        card.summary.forEachIndexed { i, s ->
            column.addView(line("${i + 1}. ${s.en}", 16f, bold = true))
            column.addView(line(s.ko, 16f))
            s.words.forEach { w ->
                column.addView(line("      ${w.ko}  —  ${w.en}", 13f))
            }
        }

        column.addView(line("Source: ${card.source}", 12f))
        column.addView(Button(this).apply {
            text = "Open the original article"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.link)))
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun line(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun heading(value: String): TextView =
        line(value, 14f, bold = true).apply {
            gravity = Gravity.START
            setPadding(0, dp(16), 0, dp(6))
        }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
