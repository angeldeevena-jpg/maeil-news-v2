// Maeil News v2: weekly quiz.
// Runs on Sundays. Reads the last 7 days of saved articles (docs/archive), asks the AI for
// 7 questions (easy to hard) plus one bonus question, and writes docs/quiz.json.
// The quiz is published directly, without approval. Questions use only the saved article text.

import { readFile, writeFile, readdir } from 'node:fs/promises';

const config = JSON.parse(await readFile('config.json', 'utf8'));
const apiKey = process.env.AI_API_KEY || '';
const price = config.price_per_million_tokens;

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

const SYSTEM = `You write a weekly news quiz for a Korean-learning app. Use only the articles provided.
Return JSON: {"questions":[ ... 8 items ... ]}
- Items 1 to 7 are the regular questions, from easiest (level 1) to hardest (level 7).
- Item 8 is the bonus question (bonus: true, level 8), harder than level 7.
- Use a different category for each regular question where possible, and mix the regions.
- Easy questions use a fact stated in the headline; hard questions use a detail from the article text.
- Each item: {"level": number, "bonus": boolean, "source_id": number, "question_en": string, "question_ko": string,
  "options": [{"en": string, "ko": string} x4], "answer": index 0-3, "explanation_en": one sentence, "explanation_ko": one sentence}
- Exactly one correct option. The wrong options must be plausible but clearly wrong from the article.
- Keep every fact exactly as the article states it. Do not add outside facts. Return only JSON.`;

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
const questions = (parsed.questions || [])
  .filter((q) => pool[q.source_id] && Array.isArray(q.options) && q.options.length === 4)
  .map((q) => {
    const src = pool[q.source_id];
    const a = articles[q.source_id];
    return {
      level: q.level,
      bonus: !!q.bonus,
      category: src.category,
      region: src.region,
      question_en: q.question_en,
      question_ko: q.question_ko,
      options: q.options,
      answer: q.answer,
      explanation_en: q.explanation_en,
      explanation_ko: q.explanation_ko,
      source: { title: a.english_title, link: a.link }
    };
  });

if (questions.length < 8) { console.log(`quiz: only ${questions.length} valid questions, kept last week's quiz`); process.exit(0); }

await writeFile('docs/quiz.json', JSON.stringify({
  generated_at: new Date().toISOString(),
  based_on_days: files.map((f) => f.slice(0, 10)),
  questions
}, null, 2) + '\n');

console.log(`quiz: ${questions.length} questions from ${articles.length} articles, cost $${cost.toFixed(4)}`);
