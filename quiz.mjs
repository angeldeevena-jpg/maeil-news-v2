// Maeil News v2: weekly quiz.
// Runs on Sundays. Reads the last 7 days of saved articles (docs/archive), asks the AI for
// 7 questions (easy to hard), one bonus question, and 7 witty remarks, then writes docs/quiz.json.
// The quiz is published directly, without approval. Questions use only the saved article text.
// The shape matches the quiz video sample: level label, category, options in English and Korean,
// the answer index, the "why" line, and one remark per question (new every week).

import { readFile, writeFile, readdir } from 'node:fs/promises';

const config = JSON.parse(await readFile('config.json', 'utf8'));
const apiKey = process.env.AI_API_KEY || '';
const price = config.price_per_million_tokens;

const CATEGORIES = ['Politics & Society', 'Business & Economy', 'Culture', 'Sports', 'Entertainment', 'World'];

if (!config.enabled) { console.log('quiz: off in config.json'); process.exit(0); }
if (!apiKey) { console.log('quiz: no AI_API_KEY, skipped'); process.exit(0); }

const usage = JSON.parse(await readFile('state/usage.json', 'utf8').catch(() => '{"spent_usd":0}'));
if ((usage.spent_usd || 0) >= config.monthly_cap_usd) { console.log('quiz: monthly cap reached, skipped'); process.exit(0); }

// Last 7 days of saved articles
const limit = Date.now() - 7 * 86400000;
const files = (await readdir('docs/archive').catch(() => []))
  .filter((f) => f.endsWith('.json') && Date.parse(f.slice(0, 10)) >= limit)
  .sort();

const articles = [];
for (const f of files) {
  const day = JSON.parse(await readFile(`docs/archive/${f}`, 'utf8'));
  for (const c of day.cards || []) articles.push(c);
}

if (articles.length < 10) { console.log(`quiz: only ${articles.length} articles this week, skipped`); process.exit(0); }

const pool = articles.map((c, id) => ({
  id,
  region: c.region,
  category: c.category,
  headline: c.english_title,
  text: (c.summary || []).map((s) => s.en).join(' ')
}));

const SYSTEM = `You write a weekly news quiz for a Korean-learning app. Use only the articles provided. Return JSON:
{"questions":[ ... 8 items ... ],
 "remarks":[ ... 7 items, one per regular question ... ]}

Questions (items 1 to 7 are regular, easy to hard; item 8 is the bonus, harder than item 7):
- "level": 1 to 8 (8 = bonus). Levels 1-3 are easy, 4-5 medium, 6-7 hard.
- "bonus": true only for item 8.
- "category": exactly one of ${JSON.stringify(CATEGORIES)}. Use a different category for each regular question where possible.
- "source_id": the id of the article the question comes from.
- "question_en" and "question_ko": the question in English and natural Korean.
- "options": exactly 4 items, each {"en": string, "ko": string}. Exactly one is correct.
- "answer": index 0 to 3 of the correct option. Vary the position across questions.
- "why_en": one sentence explaining the answer. "why_ko": the same in Korean.
- Easy questions use a fact stated in the headline; hard questions use a detail from the article text.
- Wrong options must be plausible but clearly wrong from the article.
- Keep every fact exactly as the article states it. Do not add outside facts.

Remarks (7 items, {"en": string, "kr": string}), one shown after each regular question:
- Witty, playful and a little cheeky, in the spirit of "you got it, now try the next one".
- Never reveal or hint at the answer. Each remark must be different from the others and from last week's style.
- Keep the English under 12 words and the Korean natural and polite-friendly.

Return only JSON.`;

const body = {
  model: config.model,
  response_format: { type: 'json_object' },
  messages: [
    { role: 'system', content: SYSTEM },
    { role: 'user', content: JSON.stringify(pool) }
  ]
};

const res = await fetch('https://api.openai.com/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
  body: JSON.stringify(body),
  signal: AbortSignal.timeout(300000)
});
if (!res.ok) throw new Error(`quiz AI HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);

const data = await res.json();
const cost = (data.usage.prompt_tokens / 1e6) * price.input + (data.usage.completion_tokens / 1e6) * price.output;
usage.spent_usd = (usage.spent_usd || 0) + cost;
await writeFile('state/usage.json', JSON.stringify({ ...usage, spent_usd: Number(usage.spent_usd.toFixed(5)) }, null, 2) + '\n');

const parsed = JSON.parse(data.choices[0].message.content);

function levelLabel(level, bonus) {
  if (bonus) return 'Bonus · 보너스';
  if (level <= 3) return 'Easy · 쉬움';
  if (level <= 5) return 'Medium · 보통';
  return 'Hard · 어려움';
}

const remarks = (parsed.remarks || []).filter((r) => r && r.en && r.kr);

const questions = (parsed.questions || [])
  .filter((q) =>
    pool[q.source_id] &&
    CATEGORIES.includes(q.category) &&
    Array.isArray(q.options) && q.options.length === 4 &&
    Number.isInteger(q.answer) && q.answer >= 0 && q.answer <= 3)
  .sort((a, b) => a.level - b.level)
  .map((q) => {
    const a = articles[q.source_id];
    return {
      level: q.level,
      level_label: levelLabel(q.level, !!q.bonus),
      bonus: !!q.bonus,
      category: q.category,
      region: a.region,
      question_en: q.question_en,
      question_ko: q.question_ko,
      options: q.options,
      answer: q.answer,
      why_en: q.why_en,
      why_kr: q.why_ko || q.why_kr || '',
      remark: q.bonus ? null : (remarks[q.level - 1] || null),
      source: { title: a.english_title, link: a.link }
    };
  });

const regular = questions.filter((q) => !q.bonus);
const bonus = questions.filter((q) => q.bonus);

if (regular.length < 7 || bonus.length < 1) {
  console.log(`quiz: got ${regular.length} regular and ${bonus.length} bonus questions, kept last week's quiz`);
  process.exit(0);
}

const week = (() => {
  const d = new Date();
  const t = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const day = t.getUTCDay() || 7;
  t.setUTCDate(t.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  const wk = Math.ceil(((t - yearStart) / 86400000 + 1) / 7);
  return `${t.getUTCFullYear()}-W${String(wk).padStart(2, '0')}`;
})();

await writeFile('docs/quiz.json', JSON.stringify({
  week,
  generated_at: new Date().toISOString(),
  based_on_days: files.map((f) => f.slice(0, 10)),
  intro: { en: `${regular.length} questions and a bonus. 7 seconds each.`, kr: `${regular.length}문제와 보너스 1문제. 문제당 7초.` },
  questions: [...regular, ...bonus]
}, null, 2) + '\n');

console.log(`quiz: ${regular.length} questions + ${bonus.length} bonus from ${articles.length} articles, cost $${cost.toFixed(4)}`);
