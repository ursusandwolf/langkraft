import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { chromium } from "playwright";
import { verifyLegacyMigration } from "./migration.test.js";

const directory = process.env.WEB_APP_BUNDLE ? path.resolve(process.env.WEB_APP_BUNDLE) :
  fileURLToPath(new URL("../../../webApp/build/dist/js/developmentExecutable/", import.meta.url));
const artifacts = process.env.WEB_APP_ARTIFACTS ? path.resolve(process.env.WEB_APP_ARTIFACTS) :
  fileURLToPath(new URL("../build/app/", import.meta.url));
const probes = fileURLToPath(new URL("../build/tests/", import.meta.url));
await mkdir(artifacts, { recursive: true });
// A real, decodable WAV fixture; no network, yt-dlp or ffmpeg is needed.
const audio = Buffer.alloc(44 + 16000 * 2 * 4);
audio.write("RIFF"); audio.writeUInt32LE(audio.length - 8, 4); audio.write("WAVEfmt ", 8);
audio.writeUInt32LE(16, 16); audio.writeUInt16LE(1, 20); audio.writeUInt16LE(1, 22);
audio.writeUInt32LE(16000, 24); audio.writeUInt32LE(32000, 28);
audio.writeUInt16LE(2, 32); audio.writeUInt16LE(16, 34);
audio.write("data", 36); audio.writeUInt32LE(audio.length - 44, 40);
let imports = 0;
const server = createServer(async (request, response) => {
  const pathname = new URL(request.url, "http://localhost").pathname;
  if (pathname === "/api/ingest") {
    let body = "";
    for await (const chunk of request) body += chunk;
    const { url } = JSON.parse(body);
    imports++;
    response.setHeader("Content-Type", "application/json");
    if (url.includes("invalid")) { response.writeHead(400).end('{"error":"Invalid URL"}'); return; }
    response.end(JSON.stringify({ id: "web-fixture", title: "Deutsch im Alltag", audioUrl: "/api/media/test.wav",
      localAudioPath: null, sourceUrl: url, durationSeconds: 4,
      subtitles: [{ id: "fixture-line", contentId: "web-fixture", startMs: 0, endMs: 4000,
        textDe: "Hallo Welt", textEn: null }] }));
    return;
  }
  if (pathname === "/api/media/test.wav") {
    response.setHeader("Content-Type", "audio/wav");
    response.setHeader("Content-Length", audio.length); response.end(audio); return;
  }
  const root = pathname.startsWith("/probe/") ? probes : directory;
  const resource = pathname.startsWith("/probe/") ? pathname.slice(6) : pathname;
  const file = path.resolve(root, "." + (resource === "/" ? "/index.html" : resource));
  if (!file.startsWith(path.resolve(root) + path.sep)) { response.writeHead(404).end(); return; }
  try {
    response.setHeader("Content-Type", file.endsWith(".wasm") ? "application/wasm" :
      file.endsWith(".html") ? "text/html; charset=utf-8" : "text/javascript");
    response.end(await readFile(file));
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") console.error(message.text()); });
  await page.addInitScript(() => {
    const createElement = Document.prototype.createElement;
    Document.prototype.createElement = function (...args) {
      const element = createElement.apply(this, args);
      if (args[0] === "audio") window.testAudio = element;
      return element;
    };
  });
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(() => !document.getElementById("startup"));
  // Canvas UI has no DOM text selectors in the pinned Compose version.
  // Keep a fixed viewport and save screenshots alongside behavioral assertions.
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(artifacts, "library.png") });
  console.log("Initial render", JSON.stringify(errors));
  if (process.env.APP_RENDER_ONLY) {
    assert.deepEqual(errors, []);
  } else {
    // Import failure leaves the URL editable and allows a successful retry.
    const input = page.getByRole("textbox", { name: "Ссылка на видео YouTube" });
    await input.fill("https://invalid.example/video");
    await page.waitForTimeout(150);
    const failed = page.waitForResponse(response => response.url().endsWith("/api/ingest"));
    await page.mouse.click(95, 190);
    assert.equal((await failed).status(), 400);
    await input.waitFor({ state: "visible" });
    await page.waitForFunction(() => !document.querySelector('input[type="url"]').disabled);
    await page.screenshot({ path: path.join(artifacts, "import-error.png") });
    await page.getByRole("textbox", { name: "Ссылка на видео YouTube" }).fill("https://www.youtube.com/watch?v=test");
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(artifacts, "input.png") });
    await page.mouse.click(95, 200);
    await page.waitForFunction(() => window.testAudio?.src.startsWith("blob:"), null, { timeout: 30000 });
    await page.waitForFunction(() => window.testAudio?.readyState >= 1);
    assert.equal(imports, 2);
    await page.screenshot({ path: path.join(artifacts, "player.png") });
    await page.mouse.click(600, 750);
    await page.waitForFunction(() => !window.testAudio.paused);
    await page.mouse.click(40, 175);
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(artifacts, "word.png") });
    await page.mouse.click(600, 485);
    await page.addScriptTag({ url: "/probe/harness.js" });
    const saved = await page.evaluate(async () => {
      const worker = window.storage.createDatabaseWorker();
      try {
        const request = (...args) => window.databaseRequest(worker, ...args);
        const deadline = Date.now() + 10000;
        let words;
        do {
          words = await request("exec", "SELECT word, contextSentence FROM Vocabulary");
          if (words.length) break;
          await new Promise(resolve => setTimeout(resolve, 100));
        } while (Date.now() < deadline);
        const content = await request("exec", "SELECT id, localAudioPath FROM Content");
        return { words, content };
      } finally {
        await window.databaseRequest(worker, "close"); worker.terminate();
      }
    });
    assert.deepEqual(saved.words, [["Hallo", "Hallo Welt"]]);
    assert.equal(saved.content[0][0], "web-fixture");
    assert.ok(saved.content[0][1].startsWith("indexeddb-audio:"));
    await page.mouse.click(1160, 28);
    await page.waitForFunction(() => window.testAudio.paused && !window.testAudio.getAttribute("src"));
    await page.screenshot({ path: path.join(artifacts, "words.png") });
    // Reload the real app, then disable all backend requests. Local audio must still work.
    await page.reload();
    await page.waitForFunction(() => !document.getElementById("startup"));
    await page.getByRole("textbox", { name: "Ссылка на видео YouTube" }).waitFor();
    await page.waitForTimeout(1000);
    await page.route("**/api/**", route => route.abort());
    await page.screenshot({ path: path.join(artifacts, "restored-library.png") });
    await page.mouse.click(75, 355);
    await page.waitForFunction(() => window.testAudio?.src.startsWith("blob:"));
    await page.waitForFunction(() => window.testAudio.readyState >= 1);
    await page.mouse.click(600, 750);
    await page.waitForFunction(() => !window.testAudio.paused);
    await page.mouse.click(1160, 28);
    await page.waitForFunction(() => window.testAudio.paused && !window.testAudio.getAttribute("src"));
    await page.screenshot({ path: path.join(artifacts, "restored-words.png") });
    await page.addScriptTag({ url: "/probe/harness.js" });
    const restoredWords = await page.evaluate(async () => {
      const worker = window.storage.createDatabaseWorker();
      try { return await window.databaseRequest(worker, "exec", "SELECT word, contextSentence FROM Vocabulary"); }
      finally { await window.databaseRequest(worker, "close"); worker.terminate(); }
    });
    assert.deepEqual(restoredWords, saved.words);
    assert.deepEqual(errors, []);
    console.log("PASS: import error/retry, playback, save word, release player, reload and offline audio");
    await verifyLegacyMigration(browser, `http://127.0.0.1:${server.address().port}`);
  }
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
