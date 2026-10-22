// Requires the local backend and Web dev server; intentionally excluded from npm test.
import assert from "node:assert/strict";
import { readdir, mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const source = process.env.LIVE_YOUTUBE_URL;
const downloads = process.env.LIVE_DOWNLOADS_DIR;
assert.ok(source && downloads, "Set LIVE_YOUTUBE_URL and LIVE_DOWNLOADS_DIR for the local backend");
const before = (await readdir(downloads)).sort();
const oldLogs = new Set((await readdir(os.tmpdir())).filter(name => name.startsWith("langkraft-ytdlp-")));
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  await page.goto("http://127.0.0.1:3000");
  const input = page.getByRole("textbox", { name: "Ссылка на видео YouTube" });
  await input.fill(source);
  await page.waitForTimeout(200);
  await page.mouse.click(90, 190);
  let log;
  for (let attempt = 0; attempt < 200; attempt++) {
    log = (await readdir(os.tmpdir())).find(name => name.startsWith("langkraft-ytdlp-") && !oldLogs.has(name));
    if (log) break;
    await page.waitForTimeout(50);
  }
  assert.ok(log, "The real downloader must start before clicking Cancel");
  await page.mouse.click(170, 190);
  await page.waitForFunction(() => !document.querySelector('input[type="url"]').disabled);
  for (let attempt = 0; attempt < 200; attempt++) {
    if (!(await readdir(os.tmpdir())).includes(log)) break;
    await page.waitForTimeout(50);
  }
  assert.ok(!(await readdir(os.tmpdir())).includes(log), "Downloader must stop and remove its temporary log");
  assert.deepEqual((await readdir(downloads)).sort(), before, "Cancelled import must leave no files");
  const artifacts = fileURLToPath(new URL("../build/youtube-live/", import.meta.url));
  await mkdir(artifacts, { recursive: true });
  await page.screenshot({ path: artifacts + "cancelled.png" });
  await writeFile(artifacts + "cancellation.json", JSON.stringify({ source, date: new Date().toISOString(),
    stopped: true, filesUnchanged: true }, null, 2));
  console.log("PASS: Web Cancel propagates through the dev proxy, stops yt-dlp and leaves no files");
} finally { await browser.close(); }
