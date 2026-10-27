// Maeil News v2: makes the downloadable videos and cards.
// For each Korean article (save_allowed, with Instagram data) it renders:
//   - a video from render/article.html, with its sounds
//   - 6 card images (the carousel)
// For the weekly quiz (on Sunday) it renders render/quiz.html the same way.
// Each piece is built from its own data, and its sounds come from the same timing as the page.
// Output goes to docs/media (not committed to git; the workflow publishes it as a release)
// and the URLs are written back into docs/news.json and docs/quiz.json.

import { chromium } from 'playwright';
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const run = promisify(execFile);
const ROOT = process.cwd();
const NEWS_PATH = process.env.NEWS_PATH || 'docs/news.json';
const QUIZ_PATH = process.env.QUIZ_PATH || 'docs/quiz.json';
const MEDIA_DIR = process.env.MEDIA_DIR || 'docs/media';
const MEDIA_BASE = (process.env.MEDIA_BASE || 'media').replace(/\/$/, '');
const TMP_DIR = process.env.RENDER_TMP || 'render-tmp';
const TODAY = process.env.TODAY || new Date().toISOString().slice(0, 10);
const SR = 44100;
const VIEW = { width: 1080, height: 1920 };

const dataUri = async (file, mime) =>
  `data:${mime};base64,${(await readFile(path.join(ROOT, file))).toString('base64')}`;

// ---------- sound ----------

function mulberry32(seed) {
  let a = seed;
  return () => {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Builds a mono WAV track from the page's sound events. Times are in ms from the start of the page.
function synth(events, offsetS, totalS) {
  const n = Math.ceil((totalS + offsetS + 4) * SR);
  const buf = new Float64Array(n);
  const rnd = mulberry32(7);

  // Same as the page: a tone that starts at `gain` and fades exponentially to near silence over `dur`.
  const tone = (t0, freq, dur, gain, wave) => {
    const i0 = Math.round(t0 * SR), len = Math.round(dur * SR);
    for (let k = 0; k < len; k++) {
      const i = i0 + k;
      if (i < 0 || i >= n) continue;
      const tt = k / SR, ph = 2 * Math.PI * freq * tt;
      const w = wave === 'square' ? Math.sign(Math.sin(ph))
        : wave === 'triangle' ? (2 / Math.PI) * Math.asin(Math.sin(ph))
        : Math.sin(ph);
      buf[i] += w * gain * Math.pow(0.0005 / gain, tt / dur);
    }
  };

  for (const e of events) {
    const t0 = e.t / 1000 + offsetS;
    if (e.k === 'click') {
      tone(t0, 1800 + rnd() * 400, 0.025, 0.05, 'square');
    } else if (e.k === 'tick') {
      tone(t0, 900 + e.level * 600, 0.05, 0.07 + e.level * 0.15, 'square');
    } else if (e.k === 'chime') {
      tone(t0, 523, 0.18, 0.12, 'triangle');
      tone(t0 + 0.14, 659, 0.18, 0.12, 'triangle');
      tone(t0 + 0.28, 784, 0.45, 0.14, 'triangle');
    } else if (e.k === 'intro') {
      [[262, 2.4, 0.10, 'triangle', 0], [330, 2.4, 0.10, 'triangle', 0.25], [392, 2.4, 0.10, 'triangle', 0.5],
       [523, 2.2, 0.12, 'triangle', 0.8], [784, 1.6, 0.08, 'sine', 1.2], [98, 1.2, 0.25, 'sine', 0]]
        .forEach(([f, d, g, w, o]) => tone(t0 + o, f, d, g, w));
    } else if (e.k === 'trans') {
      [262, 330, 392].forEach((f) => tone(t0, f, 1.8, 0.035, 'sine'));
      [523, 659, 784, 1047].forEach((f, i) => tone(t0 + 0.12 + i * 0.22, f, 0.55, 0.08, 'triangle'));
    }
  }

  // Loud parts are scaled down to fit; quiet tracks are left as they are.
  let peak = 1;
  for (let i = 0; i < n; i++) if (Math.abs(buf[i]) > peak) peak = Math.abs(buf[i]);
  const pcm = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) pcm.writeInt16LE(Math.round(Math.max(-1, Math.min(1, buf[i] / peak)) * 32767), i * 2);

  const header = Buffer.alloc(44);
  header.write('RIFF', 0); header.writeUInt32LE(36 + pcm.length, 4); header.write('WAVE', 8);
  header.write('fmt ', 12); header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
  header.writeUInt32LE(SR, 24); header.writeUInt32LE(SR * 2, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
  header.write('data', 36); header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

// ---------- rendering ----------

async function renderPiece(browser, { template, cardTemplate, data, name, outDir }) {
  const tmpDir = path.join(ROOT, TMP_DIR, name);
  await mkdir(tmpDir, { recursive: true });
  const url = pathToFileURL(path.join(ROOT, template)).href;
  const initScript = `window.__RENDER__ = true; window.__DATA__ = ${JSON.stringify(data)};`;

  // 1) The video, recorded in real time so the CSS animations run as they do on the page.
  const ctx = await browser.newContext({ viewport: VIEW, recordVideo: { dir: tmpDir, size: VIEW } });
  const page = await ctx.newPage();
  const video = page.video();
  const created = Date.now();
  await page.addInitScript(initScript);
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForTimeout(800);
  const total = await page.evaluate('TOTAL');
  const events = await page.evaluate('window.__events()');
  const startedAt = Date.now();
  await page.evaluate("document.getElementById('play').click()");
  const offset = (startedAt - created) / 1000; // seconds from the start of the recording to playback
  await page.waitForTimeout(total + 1500);
  await page.close();
  await ctx.close();
  const recorded = await video.path();

  const silent = path.join(tmpDir, 'silent.mp4');
  await run('ffmpeg', ['-y', '-loglevel', 'error', '-i', recorded, '-an',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-r', '30', silent]);
  const wav = path.join(tmpDir, 'audio.wav');
  await writeFile(wav, synth(events, offset, total / 1000));
  const out = path.join(outDir, `${name}-video.mp4`);
  await run('ffmpeg', ['-y', '-loglevel', 'error', '-i', silent, '-i', wav,
    '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k',
    '-shortest', '-movflags', '+faststart', out]);

  // 2) The cards: one still for each card scene, taken once its animation has finished.
  const ctx2 = await browser.newContext({ viewport: VIEW });
  const p2 = await ctx2.newPage();
  await p2.addInitScript(initScript);
  await p2.goto(pathToFileURL(path.join(ROOT, cardTemplate || template)).href, { waitUntil: 'load' });
  await p2.waitForTimeout(800);
  const cardScenes = await p2.evaluate('window.__cards');
  const cards = [];
  for (let n = 0; n < cardScenes.length; n++) {
    await p2.evaluate((i) => { playing = false; t = timeline[i].end - 400; lastRenderedScene = -1; render(); }, cardScenes[n]);
    await p2.waitForTimeout(3600); // long enough for the cover hook to fade in
    const file = path.join(outDir, `${name}-card-${n + 1}.png`);
    await p2.screenshot({ path: file });
    cards.push(file);
  }
  await ctx2.close();
  await rm(tmpDir, { recursive: true, force: true });
  return { video: out, cards };
}

// ---------- main ----------

const news = JSON.parse(await readFile(NEWS_PATH, 'utf8'));
const quiz = await readFile(QUIZ_PATH, 'utf8').then(JSON.parse).catch(() => null);
await mkdir(MEDIA_DIR, { recursive: true });
const logo = await dataUri('assets/logo.png', 'image/png');
const photo = await dataUri('assets/author.jpg', 'image/jpeg');

const log = [];
const browser = await chromium.launch();
try {
  for (const card of news.cards || []) {
    if (!card.save_allowed || !card.instagram || !card.id || !card.is_new) continue; // new articles only
    try {
      const r = await renderPiece(browser, { template: 'render/article.html', cardTemplate: 'render/cards.html', data: { card, logo, photo }, name: card.id, outDir: MEDIA_DIR });
      card.media = {
        video: `${MEDIA_BASE}/${path.basename(r.video)}`,
        cards: r.cards.map((f) => `${MEDIA_BASE}/${path.basename(f)}`)
      };
      log.push(`article ${card.id}: video and ${r.cards.length} cards`);
    } catch (err) {
      card.media = null;
      log.push(`article ${card.id}: FAILED (${err.message})`);
    }
  }

  if (quiz && Array.isArray(quiz.questions) && (process.env.FORCE_QUIZ || (quiz.generated_at || '').slice(0, 10) === TODAY)) {
    const name = `quiz-${quiz.week || TODAY}`;
    try {
      const r = await renderPiece(browser, { template: 'render/quiz.html', data: { quiz, logo, photo }, name, outDir: MEDIA_DIR });
      quiz.media = {
        video: `${MEDIA_BASE}/${path.basename(r.video)}`,
        cards: r.cards.map((f) => `${MEDIA_BASE}/${path.basename(f)}`)
      };
      log.push(`quiz ${name}: video and ${r.cards.length} cards`);
    } catch (err) {
      quiz.media = null;
      log.push(`quiz ${name}: FAILED (${err.message})`);
    }
  }
} finally {
  await browser.close();
}

await writeFile(NEWS_PATH, JSON.stringify(news, null, 2) + '\n');
if (quiz) await writeFile(QUIZ_PATH, JSON.stringify(quiz, null, 2) + '\n');
console.log(log.length ? log.join('\n') : 'nothing to render today');
