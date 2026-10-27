// Maeil News v2: daily news collector.
// Reads the Korean, Indian and global RSS feeds, asks the AI for Korean translations and
// short summaries, and writes docs/news.json (for the app and the web page).
// Stores only headlines, short summaries, source names and links. No article images or full text.

import { readFile, writeFile, mkdir } from 'node:fs/promises';

const config = JSON.parse(await readFile('config.json', 'utf8'));
const today = new Date().toISOString().slice(0, 10);
const now = new Date();
const month = today.slice(0, 7);
const MAX_PER_REGION = 6;      // new articles per region per run
const SEEN_DAYS = 14;          // remember links this long, so nothing repeats

const REGIONS = {
  korean: {
    label: 'Korean news',
    feeds: [
      { source: 'The Korea Herald', category: 'Politics & Society', url: 'https://www.koreaherald.com/rss/kh_National' },
      { source: 'The Korea Herald', category: 'Business', url: 'https://www.koreaherald.com/rss/kh_Business' },
      { source: 'The Korea Herald', category: 'Culture', url: 'https://www.koreaherald.com/rss/kh_LifenCulture' },
      { source: 'The Korea Herald', category: 'Sports', url: 'https://www.koreaherald.com/rss/kh_Sports' },
      { source: 'The Korea Herald', category: 'Entertainment', url: 'https://www.koreaherald.com/rss/kh_Kpop' },
      { source: 'The Korea Herald', category: 'World', url: 'https://www.koreaherald.com/rss/kh_World' }
    ]
  },
  indian: {
    label: 'Indian news',
    feeds: [
      { source: 'The Hindu', category: 'Politics & Society', url: 'https://www.thehindu.com/news/national/feeder/default.rss' },
      { source: 'The Hindu', category: 'Business', url: 'https://www.thehindu.com/business/feeder/default.rss' },
      { source: 'The Hindu', category: 'Culture', url: 'https://www.thehindu.com/life-and-style/feeder/default.rss' },
      { source: 'The Hindu', category: 'Sports', url: 'https://www.thehindu.com/sport/feeder/default.rss' },
      { source: 'The Hindu', category: 'Entertainment', url: 'https://www.thehindu.com/entertainment/feeder/default.rss' }
    ]
  },
  global: {
    label: 'Global news',
    feeds: [
      { source: 'The Hindu International', category: 'World', url: 'https://www.thehindu.com/news/international/feeder/default.rss' }
    ]
  }
};

// ---------- small helpers ----------

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

async function fetchFeed(feed) {
  try {
    const res = await fetch(feed.url, {
      headers: { 'User-Agent': 'MaeilNewsBot/2.0 (daily headlines; contact via repository)' },
      signal: AbortSignal.timeout(25000)
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return parseItems(await res.text()).map((i) => ({ ...i, source: feed.source, category: feed.category }));
  } catch (err) {
    console.log(`feed failed: ${feed.url} (${err.message})`);
    return [];
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

async function translateBatch(items) {
  const system = `You write for a Korean-learning news site. For each numbered English news item, return JSON:
{"cards":[{"index":number,"korean_title":"natural Korean headline, under 30 characters","korean_summary":"one or two natural Korean sentences, polite style (-요/-습니다)","english_summary":"one plain English sentence, under 30 words"}]}
Keep facts exactly as the article states them. Do not add facts. Return only JSON.`;
  const body = {
    model: config.model,
    response_format: { type: 'json_object' },
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: JSON.stringify(items.map((it, index) => ({ index, title: it.title, description: it.description }))) }
    ]
  };
  const res = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120000)
  });
  if (!res.ok) throw new Error(`AI HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const cost = (data.usage.prompt_tokens / 1e6) * price.input + (data.usage.completion_tokens / 1e6) * price.output;
  usage.spent_usd += cost;
  const parsed = JSON.parse(data.choices[0].message.content);
  return { cards: parsed.cards || [], cost };
}

// ---------- main ----------

let status = 'ai';
let note = '';

if (!config.enabled) {
  status = 'off';
  note = 'Turned off in config.json. No AI calls, no new articles.';
} else if (!apiKey) {
  status = 'no key';
  note = 'AI_API_KEY is not set. Articles are English only.';
} else if (usage.spent_usd >= config.monthly_cap_usd) {
  status = 'cap reached';
  note = `Monthly limit of $${config.monthly_cap_usd} reached. Articles are English only until next month.`;
}

const outCards = [];
const newLinks = [];

if (status !== 'off') {
  for (const [regionKey, region] of Object.entries(REGIONS)) {
    const fetched = (await Promise.all(region.feeds.map(fetchFeed))).flat();
    const fresh = fetched
      .filter((i) => !seen[i.link])
      .sort((a, b) => (Date.parse(b.published) || 0) - (Date.parse(a.published) || 0))
      .slice(0, MAX_PER_REGION);

    if (fresh.length === 0) { console.log(`${regionKey}: no new articles`); continue; }

    let translated = [];
    if (status === 'ai') {
      try {
        const r = await translateBatch(fresh);
        translated = r.cards;
      } catch (err) {
        console.log(`${regionKey}: AI failed, English only (${err.message})`);
        note += ` ${region.label}: AI call failed, English only.`;
      }
    }

    fresh.forEach((item, index) => {
      const t = translated.find((c) => c.index === index) || {};
      outCards.push({
        region: regionKey,
        category: item.category,
        source: item.source,
        link: item.link,
        published: item.published,
        english_title: item.title,
        english_summary: t.english_summary || item.description.slice(0, 200),
        korean_title: t.korean_title || '',
        korean_summary: t.korean_summary || ''
      });
      seen[item.link] = Date.now();
      newLinks.push(item.link);
    });
    console.log(`${regionKey}: ${fresh.length} new`);
  }
}

// ---------- write outputs ----------

const newsPath = 'docs/news.json';
const previous = await readJson(newsPath, { cards: [] });
const cards = outCards.length ? outCards : (status === 'off' ? [] : previous.cards || []);
const finalNote = outCards.length
  ? `${outCards.length} new articles. ${note}`.trim()
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

console.log(`status=${status} new=${outCards.length} spent_this_month=$${usage.spent_usd.toFixed(4)}`);
