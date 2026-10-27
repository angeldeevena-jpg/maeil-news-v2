package com.maeilnews.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The word bank, as on the design board "word bank" (390 x 450, red): the words of the Korean
 * title, three at a time, each with its romanisation, meaning and a play button.
 */
class WordBankActivity : Activity() {

    private val red = Color.parseColor("#FFE8432D")
    private val cream = Color.parseColor("#FFFFF8EE")
    private val dark = Color.parseColor("#FF1F1A17")
    private val yellow = Color.parseColor("#FFFFD21F")
    private val perPage = 3

    private var page = 0
    private lateinit var root: LinearLayout
    private var words: List<Word> = emptyList()
    private var koreanTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(red)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        setContentView(root)

        val id = intent.getStringExtra(DetailActivity.EXTRA_ID)
        val card = NewsStore.loadNews(this)?.cards?.firstOrNull { it.id == id }
            ?: NewsWidgetProvider.currentCard(this)
        words = card?.titleWords ?: emptyList()
        koreanTitle = card?.koreanTitle ?: ""
        render()
    }

    private fun pages(): Int = maxOf(1, (words.size + perPage - 1) / perPage)

    private fun render() {
        root.removeAllViews()
        val total = pages()
        val from = page * perPage
        val to = minOf(words.size, from + perPage)

        // Header: title on the left, page number on the right
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("Word bank · 단어장", 14f, cream).apply { letterSpacing = 0.08f; isAllCaps = true },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(label("${page + 1} / $total", 14f, cream))
        root.addView(header, matchWrap())

        root.addView(
            label(koreanTitle, 16f, cream, korean = true),
            matchWrap(top = 6)
        )

        // The word cards for this page
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in from until to) {
            list.addView(wordCard(words[i]), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = if (i == from) dp(0) else dp(8) })
        }
        if (words.isEmpty()) list.addView(label("No word list for this article.", 14f, cream))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })

        // Footer: previous, range, next
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(circle("←", dark, cream).apply {
            setOnClickListener { if (page > 0) { page--; render() } }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        footer.addView(
            label(
                if (words.isEmpty()) "" else "Words ${from + 1}–$to of ${words.size}",
                14f, cream, bold = true, center = true
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        footer.addView(circle("→", yellow, dark).apply {
            setOnClickListener { if (page < total - 1) { page++; render() } }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(footer, matchWrap(top = 8))
    }

    private fun wordCard(w: Word): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(cream)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        row.addView(TextView(this).apply {
            text = w.ko
            textSize = 30f
            setTextColor(dark)
            setTypeface(Typeface.DEFAULT_BOLD)
            minWidth = dp(110)
        })
        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        middle.addView(label(w.roman, 17f, red, bold = true))
        middle.addView(label(w.en, 16f, dark))
        row.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        row.addView(
            circle("▶", yellow, dark, 13f, dp(34)).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(yellow)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { Speaker.speak(this@WordBankActivity, w.ko, Locale.KOREAN, 0.5f) }
            }
        )
        return row
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        korean: Boolean = false,
        center: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER
    }

    private fun circle(value: String, bg: Int, fg: Int, size: Float = 18f, diameter: Int = dp(36)): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(fg)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            layoutParams = LinearLayout.LayoutParams(diameter, diameter)
        }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
