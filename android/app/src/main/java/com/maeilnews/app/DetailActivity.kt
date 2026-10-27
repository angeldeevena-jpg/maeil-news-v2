package com.maeilnews.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URLEncoder
import java.util.Locale

/**
 * The summary popup, as on the design board "summary popup" (390 x 844):
 * dark body, orange header with "Search on Google" and a close button, then five numbered
 * sentences, each in English and Korean, with a Listen button for each.
 */
class DetailActivity : Activity() {

    private val dark = Color.parseColor("#FF1F1A17")
    private val cream = Color.parseColor("#FFFFF8EE")
    private val yellow = Color.parseColor("#FFFFD21F")
    private val orange = Color.parseColor("#FFFF8C1A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val card = NewsStore.loadNews(this)?.cards?.firstOrNull { it.id == id }
            ?: NewsWidgetProvider.currentCard(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
        }
        setContentView(root)

        if (card == null) {
            root.addView(text("No article loaded. Go back and tap refresh.", 16f, cream, false), matchWrap())
            return
        }

        // Orange header band
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(orange)
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            pill("Search on Google → 구글 검색", dark, yellow, 14f, dp(16), dp(12)).apply {
                setOnClickListener {
                    val query = URLEncoder.encode(card.koreanTitle, "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query")))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(
            circle("×", dark, cream, 18f, dp(38)).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(38), dp(38))
        )
        band.addView(topRow, matchWrap())

        band.addView(
            text(card.category + " · " + regionLabel(card.region), 12f, dark, true).apply {
                letterSpacing = 0.08f
                isAllCaps = true
            },
            matchWrap(top = 10)
        )
        band.addView(text(card.englishTitle, 21f, dark, true).apply { setLineSpacing(0f, 1.1f) }, matchWrap(top = 6))
        band.addView(text(card.koreanTitle, 15f, dark, true, korean = true), matchWrap(top = 6))
        root.addView(band, matchWrap())

        // Five sentences
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(22))
        }
        card.summary.forEachIndexed { i, s ->
            val n = i + 1
            list.addView(sentence(n, s.en, s.ko, last = i == card.summary.size - 1), matchWrap())
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun sentence(n: Int, en: String, ko: String, last: Boolean): LinearLayout {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val enRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        enRow.addView(text("$n · ENGLISH", 12f, yellow, true).apply { letterSpacing = 0.05f }, weightWrap())
        enRow.addView(
            pill("▶ Listen", yellow, dark, 12f, dp(10), dp(4)).apply {
                setOnClickListener { Speaker.speak(this@DetailActivity, en, Locale.US, 1.0f) }
            }
        )
        block.addView(enRow, matchWrap())

        block.addView(text(en, 16f, cream, true, lineHeight = 1.35f), matchWrap(top = 6))

        val koRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        koRow.addView(text("$n · 한국어", 12f, orange, true, korean = true), weightWrap())
        koRow.addView(
            pill("▶ 듣기 (very slow)", orange, dark, 12f, dp(10), dp(4)).apply {
                setOnClickListener { Speaker.speak(this@DetailActivity, ko, Locale.KOREAN, 0.4f) }
            }
        )
        block.addView(koRow, matchWrap(top = 4))

        block.addView(text(ko, 15f, cream, false, korean = true, lineHeight = 1.45f), matchWrap(top = 6))

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(block, matchWrap())
        if (!last) {
            container.addView(View(this).apply { setBackgroundColor(Color.parseColor("#33FFF8EE")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        container.setPadding(0, 0, 0, if (last) 0 else dp(14))
        return container
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        korean: Boolean = false,
        lineHeight: Float = 1.2f
    ): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, lineHeight)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun pill(label: String, bg: Int, fg: Int, size: Float, padH: Int, padV: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(fg)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(bg)
            }
        }

    private fun circle(label: String, bg: Int, fg: Int, size: Float, diameter: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(fg)
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

    private fun weightWrap() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "id"
    }
}
