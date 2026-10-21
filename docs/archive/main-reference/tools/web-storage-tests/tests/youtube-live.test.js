// Opt-in external integration check. Never run as part of the offline test suite.
import assert from "node:assert/strict";
import { createServer, request as proxyRequest } from "node:http";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { chromium } from "playwright";

const source = process.env.LIVE_YOUTUBE_URL;
assert.ok(source?.startsWith("https://"), "Set LIVE_YOUTUBE_URL to a short video with German captions");
const directory = fileURLToPath(new URL("../../../webApp/build/dist/js/productionExecutable/", import.meta.url));
const artifacts = fileURLToPath(new URL("../build/youtube-live/", import.meta.url));
await mkdir(artifacts, { recursive: true });
const server = createServer(async (request, response) => {
  if (request.url.startsWith("/api/")) {
    const proxy = proxyRequest({ hostname: "127.0.0.1", port: 8080, path: request.url,
      method: request.method, headers: request.headers, timeout: 660000 }, upstream => {
      response.writeHead(upstream.statusCode, upstream.headers); upstream.pipe(response);
    });
    proxy.on("error", () => { if (!response.headersSent) response.writeHead(502); response.end(); });
    proxy.on("timeout", () => proxy.destroy());
    response.on("close", () => { if (!response.writableEnded) proxy.destroy(); });
    request.pipe(proxy);
    return;
  }
  const pathname = new URL(request.url, "http://localhost").pathname;
  const file = path.resolve(directory, "." + (pathname === "/" ? "/index.html" : pathname));
  if (!file.startsWith(path.resolve(directory) + path.sep)) { response.writeHead(404).end(); return; }
  try {
    response.setHeader("Content-Type", file.endsWith(".wasm") ? "application/wasm" :
      file.endsWith(".html") ? "text/html; charset=utf-8" : "text/javascript");
    response.end(await readFile(file));
  } catch { response.writeHead(404).end(); }
});
await new Promise((resolve, reject) => { server.on("error", reject); server.listen(3000, "127.0.0.1", resolve); });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(() => {
    const original = Document.prototype.createElement;
    Document.prototype.createElement = function (...args) {
      const element = original.apply(this, args);
      if (args[0] === "audio") window.testAudio = element;
      return element;
    };
  });
  await page.goto("http://127.0.0.1:3000");
  const input = page.getByRole("textbox", { name: "Ссылка на видео YouTube" });
  await input.fill(source);
  await page.waitForTimeout(200);
  const imported = page.waitForResponse(response => response.url().endsWith("/api/ingest"), { timeout: 660000 });
  await page.mouse.click(90, 190);
  const response = await imported;
  const content = await response.json();
  await writeFile(path.join(artifacts, "response.json"), JSON.stringify(content, null, 2));
  assert.equal(response.status(), 200, JSON.stringify(content));
  assert.ok(content.subtitles.length > 0);
  assert.equal(content.sourceUrl, source);
  assert.ok(content.audioUrl.startsWith("http://127.0.0.1:3000/api/media/"));
  assert.ok(content.audioUrl.endsWith(`/${content.id}.opus`));
  await page.waitForFunction(() => window.testAudio?.src.startsWith("blob:"), null, { timeout: 60000 });
  await page.waitForFunction(() => window.testAudio.readyState >= 1);
  const duration = await page.evaluate(() => window.testAudio.duration);
  assert.ok(Math.abs(duration - content.durationSeconds) < 3);
  await page.mouse.click(600, 750);
  await page.waitForFunction(() => !window.testAudio.paused && window.testAudio.currentTime > 0);
  await page.mouse.click(600, 750);
  await page.waitForFunction(() => window.testAudio.paused);
  await page.evaluate(() => { window.testAudio.currentTime = 10; });
  await page.mouse.click(1150, 180); // First cue, outside its words.
  await page.waitForFunction(start => Math.abs(window.testAudio.currentTime - start / 1000) < 0.3,
    content.subtitles[0].startMs);
  await page.screenshot({ path: path.join(artifacts, "player.png") });
  const range = await fetch(content.audioUrl, { headers: { Range: "bytes=0-63" } });
  assert.equal(range.status, 206);
  assert.equal((await range.arrayBuffer()).byteLength, 64);
  assert.ok(range.headers.get("content-range").startsWith("bytes 0-63/"));
  const full = await fetch(content.audioUrl);
  assert.equal(full.status, 200);
  const audioFile = path.join(artifacts, "audio.opus");
  await writeFile(audioFile, Buffer.from(await full.arrayBuffer()));
  const probe = JSON.parse(execFileSync(process.env.FFPROBE_EXECUTABLE || "ffprobe",
    ["-v", "error", "-show_entries", "stream=codec_name:format=duration", "-of", "json", audioFile], { encoding: "utf8" }));
  assert.equal(probe.streams[0].codec_name, "opus");
  assert.ok(Math.abs(Number(probe.format.duration) - content.durationSeconds) < 3);
  const invalid = await fetch("http://127.0.0.1:3000/api/ingest", { method: "POST",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url: "https://example.com/video" }) });
  assert.equal(invalid.status, 400);
  assert.deepEqual(errors, []);
  const report = { date: new Date().toISOString(), source, title: content.title, duration,
    cues: content.subtitles.length, codec: probe.streams[0].codec_name,
    range: range.headers.get("content-range"), invalidStatus: invalid.status,
    checks: ["real import", "local Blob playback", "cue seek", "HTTP Range", "ffprobe", "invalid URL"] };
  await writeFile(path.join(artifacts, "report.json"), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await browser.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}
