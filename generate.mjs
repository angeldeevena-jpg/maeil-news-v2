// Maeil News v2: daily news collector.
// For each region, takes the newest unseen articles per category (fixed counts), reads each
// article's main text, and asks the AI for:
//   - a Korean title with a translation of each word
//   - a 5-sentence summary in English, with the same 5 sentences in Korean
//   - a translation of each Korean word in those sentences
// Writes docs/news.json. Stores only the AI's summary and words, the headline, the source and the
// link. Article text is read for the summary and is not stored.

import { readFile, writeFile, mkdir } from 'node:fs/promises';

const config = JSON.parse(await readFile('config.json', 'utf8'));
const today = new Date().toISOString().slice(0, 10);
const now = new Date();
const month = today.slice(0, 7);
const SEEN_DAYS = 14;        // remember links this long, so nothing repeats
const ARTICLE_CHARS = 4000;  // article text sent to the AI, per article
const AI_BATCH = 8;          // articles per AI call

// Categories: Politics & Society, Business & Economy, Culture, Sports, Entertainment, World.
// quota = articles per category per day.
const REGIONS = {
  korean: {
    label: 'Korean news',
    feeds: [
      { source: 'The Korea Herald', category: 'Politics & Society', quota: 4, url: 'https://www.koreaherald.com/rss/kh_National' },
      { source: 'The Korea Herald', category: 'Business & Economy', quota: 4, url: 'https://www.koreaherald.com/rss/kh_Business' },
      { source: 'The Korea Herald', category: 'Culture', quota: 4, url: 'https://www.koreaherald.com/rss/kh_LifenCulture' },
      { source: 'The Korea Herald', category: 'Sports', quota: 4, url: 'https://www.koreaherald.com/rss/kh_Sports' },
      { source: 'The Korea Herald', category: 'Entertainment', quota: 4, url: 'https://www.koreaherald.com/rss/kh_Kpop' },
      { source: 'The Korea Herald', category: 'World', quota: 4, url: 'https://www.koreaherald.com/rss/kh_World' }
    ]
  },
  indian: {
    label: 'Indian news',
    feeds: [
      { source: 'The Hindu', category: 'Politics & Society', quota: 2, url: 'https://www.thehindu.com/news/national/feeder/default.rss' },
      { source: 'The Hindu', category: 'Business & Economy', quota: 2, url: 'https://www.thehindu.com/business/feeder/default.rss' },
      { source: 'The Hindu', category: 'Culture', quota: 2, url: 'https://www.thehindu.com/life-and-style/feeder/default.rss' },
      { source: 'The Hindu', category: 'Sports', quota: 2, url: 'https://www.thehindu.com/sport/feeder/default.rss' },
      { source: 'The Hindu', category: 'Entertainment', quota: 2, url: 'https://www.thehindu.com/entertainment/feeder/default.rss' }
    ]
  },
  global: {
    label: 'Global news',
    feeds: [
      { source: 'The Hindu International', category: 'World', quota: 6, url: 'https://www.thehindu.com/news/international/feeder/default.rss' }
    ]
  }
};

// ---------- helpers ----------

function clean(s = '') {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;|&#x27;|&apos;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ').trim();
}

function tag(xml, name) {
  const m = xml.match(new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`));
  return m ? clean(m[1]) : '';
}

function parseItems(xml) {
  return [...xml.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/g)].map((m) => {
    const x = m[1];
    return {
      title: tag(x, 'title'),
      link: tag(x, 'link'),
      description: tag(x, 'description').slice(0, 500),
      published: tag(x, 'pubDate')
    };
  }).filter((i) => i.title && i.link);
}

const UA = { 'User-Agent': 'MaeilNewsBot/2.0 (daily headlines; contact via repository)' };

async function fetchFeed(feed) {
  try {
    const res = await fetch(feed.url, { headers: UA, signal: AbortSignal.timeout(25000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return parseItems(await res.text()).map((i) => ({ ...i, source: feed.source, category: feed.category }));
  } catch (err) {
    console.log(`feed failed: ${feed.url} (${err.message})`);
    return [];
  }
}

// Reads the article page and keeps the paragraphs of the main text.
async function fetchArticleText(url) {
  try {
    const res = await fetch(url, { headers: UA, signal: AbortSignal.timeout(25000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const html = await res.text();
    const paras = [...html.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/g)]
      .map((m) => clean(m[1]))
      .filter((p) => p.length >= 60 && !/cookie|subscribe|copyright|advertisement/i.test(p));
    return paras.join('\n').slice(0, ARTICLE_CHARS);
  } catch (err) {
    console.log(`article failed: ${url} (${err.message})`);
    return '';
  }
}

// ---------- state and budget ----------

async function readJson(path, fallback) {
  try { return JSON.parse(await readFile(path, 'utf8')); } catch { return fallback; }
}

const seen = await readJson('state/seen.json', {});
const usage = await readJson('state/usage.json', { month, spent_usd: 0 });
if (usage.month !== month) { usage.month = month; usage.spent_usd = 0; }

const cutoff = Date.now() - SEEN_DAYS * 86400000;
for (const link of Object.keys(seen)) if (seen[link] < cutoff) delete seen[link];

// ---------- AI ----------

const apiKey = process.env.AI_API_KEY || '';
const price = config.price_per_million_tokens;

const SYSTEM = `You write for a Korean-learning news app. For each numbered news item (English headline and article text), return JSON:
{"cards":[{
  "index": number,
  "korean_title": "natural Korean headline, under 30 characters",
  "korean_title_words": [{"ko": "Korean word as it appears in korean_title", "en": "short English meaning"}],
  "summary": [
    {"en": "one English sentence from the article, 5 sentences in total in story order",
     "ko": "natural Korean translation of that sentence, polite style (-요/-습니다)",
     "words": [{"ko": "each Korean word in the ko sentence, as it appears (particles attached are fine)", "en": "short English meaning"}]}
  ]
}]}
Rules: exactly 5 summary items. Facts must match the article; do not add facts. If the article text is missing, write the 5 sentences from the headline and say so in nothing else. Return only JSON.`;

async function translateBatch(items) {
  const body = {
    model: config.model,
    response_format: { type: 'json_object' },
    messages: [
      { role: 'system', content: SYSTEM },
      { role: 'user', content: JSON.stringify(items.map((it, index) => ({
          index,
          headline: it.title,
          article: it.text || it.description
        }))) }
    ]
  };
  const res = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(300000)
  });
  if (!res.ok) throw new Error(`AI HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const cost = (data.usage.prompt_tokens / 1e6) * price.input + (data.usage.completion_tokens / 1e6) * price.output;
  usage.spent_usd += cost;
  const parsed = JSON.parse(data.choices[0].message.content);
  return parsed.cards || [];
}

// ---------- main ----------

let status = 'ai';
let note = '';

if (!config.enabled) {
  status = 'off';
  note = 'Turned off in config.json. No AI calls, no new articles.';
} else if (!apiKey) {
  status = 'no key';
  note = 'AI_API_KEY is not set. Articles are English headlines only.';
} else if (usage.spent_usd >= config.monthly_cap_usd) {
  status = 'cap reached';
  note = `Monthly limit of $${config.monthly_cap_usd} reached. Articles are English headlines only until next month.`;
}

const outCards = [];

if (status !== 'off') {
  // Regions run in order (Korean, Indian, Global) so an article is never used twice.
  for (const [regionKey, region] of Object.entries(REGIONS)) {
    const picked = [];
    for (const feed of region.feeds) {
      const items = await fetchFeed(feed);
      const fresh = items
        .filter((i) => !seen[i.link] && !picked.some((p) => p.link === i.link) && !outCards.some((c) => c.link === i.link))
        .sort((a, b) => (Date.parse(b.published) || 0) - (Date.parse(a.published) || 0))
        .slice(0, feed.quota);
      if (fresh.length < feed.quota) {
        console.log(`${regionKey}/${feed.category}: only ${fresh.length} of ${feed.quota} new`);
      }
      picked.push(...fresh);
    }

    if (picked.length === 0) { console.log(`${regionKey}: no new articles`); continue; }

    // Read each article's text (only needed when the AI is on).
    if (status === 'ai') {
      for (const item of picked) item.text = await fetchArticleText(item.link);
    }

    const results = [];
    if (status === 'ai') {
      for (let i = 0; i < picked.length; i += AI_BATCH) {
        const chunk = picked.slice(i, i + AI_BATCH);
        try {
          const cards = await translateBatch(chunk);
          for (const c of cards) results[i + c.index] = c;
        } catch (err) {
          console.log(`${regionKey}: AI batch failed (${err.message})`);
          note += ` ${region.label}: some articles have English headlines only.`;
        }
      }
    }

    picked.forEach((item, index) => {
      const t = results[index] || {};
      outCards.push({
        region: regionKey,
        category: item.category,
        source: item.source,
        link: item.link,
        published: item.published,
        english_title: item.title,
        korean_title: t.korean_title || '',
        korean_title_words: t.korean_title_words || [],
        summary: t.summary || [],
        save_allowed: regionKey === 'korean' // save cards, Instagram post and video: Korean articles only
      });
      seen[item.link] = Date.now();
    });
    console.log(`${regionKey}: ${picked.length} articles`);
  }
}

// ---------- write outputs ----------

const newsPath = 'docs/news.json';
const previous = await readJson(newsPath, { cards: [] });
const cards = outCards.length ? outCards : (status === 'off' ? [] : previous.cards || []);
const finalNote = outCards.length
  ? `${outCards.length} articles. ${note}`.trim()
  : (note || 'No new articles this run. Showing the last set.');

await mkdir('docs', { recursive: true });
await mkdir('state', { recursive: true });

await writeFile(newsPath, JSON.stringify({
  date: today,
  generated_at: now.toISOString(),
  status,
  note: finalNote,
  cards
}, null, 2) + '\n');

await writeFile('state/seen.json', JSON.stringify(seen, null, 2) + '\n');
await writeFile('state/usage.json', JSON.stringify({ ...usage, spent_usd: Number(usage.spent_usd.toFixed(5)) }, null, 2) + '\n');

console.log(`status=${status} articles=${outCards.length} spent_this_month=$${usage.spent_usd.toFixed(4)}`);
