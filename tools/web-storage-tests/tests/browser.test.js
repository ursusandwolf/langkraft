import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { chromium } from "playwright";

const directory = path.resolve(fileURLToPath(new URL("../build/tests/", import.meta.url)));
const server = createServer(async (request, response) => {
  const pathname = new URL(request.url, "http://localhost").pathname;
  if (pathname === "/") {
    response.setHeader("Content-Type", "text/html");
    response.end('<!doctype html><title>Storage tests</title><script src="/harness.js"></script>');
    return;
  }
  const file = path.resolve(directory, "." + pathname);
  if (!file.startsWith(directory + path.sep) && file !== directory) {
    response.writeHead(404).end(); return;
  }
  try {
    response.setHeader("Content-Type", file.endsWith(".wasm") ? "application/wasm" : "text/javascript");
    response.end(await readFile(file));
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(() => !!window.storage);
  const location = await page.evaluate(async () => {
    const worker = window.storage.createDatabaseWorker();
    const request = (...args) => window.databaseRequest(worker, ...args);
    await request("exec", "CREATE TABLE Probe (id TEXT PRIMARY KEY, value INTEGER)");
    await request("begin_transaction");
    await request("exec", "INSERT INTO Probe VALUES (?, ?)", ["persisted", 1700000000000]);
    await request("end_transaction");
    // Force termination, as on page unload, after the commit was acknowledged.
    worker.terminate();
    const token = await window.storage.beginAudio("content");
    await window.storage.appendAudio(token, new Uint8Array([1, 2]));
    await window.storage.appendAudio(token, new Uint8Array([3, 4]));
    return window.storage.commitAudio(token, 4);
  });
  await page.reload();
  await page.waitForFunction(() => !!window.storage);
  const restored = await page.evaluate(async location => {
    const worker = window.storage.createDatabaseWorker();
    const rows = await window.databaseRequest(worker, "exec", "SELECT id, value FROM Probe");
    const blob = await window.storage.readAudioBlob(location);
    const url = URL.createObjectURL(blob);
    const bytes = [...new Uint8Array(await (await fetch(url)).arrayBuffer())];
    URL.revokeObjectURL(url);
    await window.databaseRequest(worker, "close");
    worker.terminate();
    return { rows, bytes };
  }, location);
  assert.deepEqual(restored.rows, [["persisted", 1700000000000]]);
  assert.deepEqual(restored.bytes, [1, 2, 3, 4]);
  console.log("PASS: SQLite and audio survive page reload and worker termination");

  const rollback = await page.evaluate(async () => {
    const worker = window.storage.createDatabaseWorker();
    const request = (...args) => window.databaseRequest(worker, ...args);
    await request("begin_transaction");
    await request("exec", "UPDATE Probe SET value=1");
    await request("rollback_transaction");
    const rows = await request("exec", "SELECT value FROM Probe");
    await request("exec", "CREATE TABLE Parent(id INTEGER PRIMARY KEY)");
    await request("exec", "CREATE TABLE Child(parentId INTEGER REFERENCES Parent(id))");
    let rejected = false;
    try { await request("exec", "INSERT INTO Child VALUES (999)"); } catch { rejected = true; }
    await request("close");
    worker.terminate();
    return { rows, rejected };
  });
  assert.deepEqual(rollback.rows, [[1700000000000]]);
  assert.equal(rollback.rejected, true);
  console.log("PASS: worker rollback and foreign-key enforcement");

  const aborted = await page.evaluate(async () => {
    const token = await window.storage.beginAudio("partial");
    await window.storage.appendAudio(token, new Uint8Array([1]));
    let rejected = false;
    try { await window.storage.commitAudio(token, 10); } catch { rejected = true; }
    let hidden = false;
    try { await window.storage.readAudioBlob("indexeddb-audio:" + token); } catch { hidden = true; }
    await window.storage.abortAudio(token);
    let removed = false;
    try { await window.storage.readAudioBlob("indexeddb-audio:" + token); } catch { removed = true; }
    return { rejected, hidden, removed };
  });
  assert.deepEqual(aborted, { rejected: true, hidden: true, removed: true });
  console.log("PASS: incomplete audio is hidden and abort removes its chunks");
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
