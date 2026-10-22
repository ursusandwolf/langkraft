import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { chromium } from "playwright";
import { waitForAsyncCondition } from "./async-wait.js";

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
  const context = await browser.newContext();
  const page = await context.newPage();
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

  // Both pages share one origin and IndexedDB. Closing the owner must release
  // its lifetime lock, while an open owner remains safe even with no network IO.
  const writer = await page.context().newPage();
  await writer.goto(page.url());
  await writer.waitForFunction(() => !!window.storage);
  const abandoned = await writer.evaluate(async () => {
    const id = await window.storage.beginAudio("abandoned");
    await window.storage.appendAudio(id, new Uint8Array([5, 6]));
    return id;
  });
  const snapshot = id => page.evaluate(async id => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("langkraft-audio", 1);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(["audio", "chunks"], "readonly");
        const manifest = tx.objectStore("audio").get(id);
        const chunks = tx.objectStore("chunks").count(IDBKeyRange.bound([id, 0], [id, Infinity]));
        tx.oncomplete = () => resolve({ exists: !!manifest.result, chunks: chunks.result });
        tx.onabort = () => reject(tx.error);
      });
    } finally { db.close(); }
  }, id);
  const triggerCleanup = () => page.evaluate(async () => {
    const id = await window.storage.beginAudio("cleanup-trigger");
    await window.storage.abortAudio(id);
  });
  await triggerCleanup();
  assert.deepEqual(await snapshot(abandoned), { exists: true, chunks: 1 });
  const foreignWrites = await page.evaluate(async id => {
    const rejected = [];
    for (const operation of [
      () => window.storage.appendAudio(id, new Uint8Array([9])),
      () => window.storage.commitAudio(id, 2),
      () => window.storage.abortAudio(id)
    ]) {
      try { await operation(); rejected.push(false); } catch { rejected.push(true); }
    }
    return rejected;
  }, abandoned);
  assert.deepEqual(foreignWrites, [true, true, true]);
  await writer.evaluate(async id => {
    await window.storage.appendAudio(id, new Uint8Array([7]));
  }, abandoned);
  assert.deepEqual(await snapshot(abandoned), { exists: true, chunks: 2 });
  await writer.close();
  await waitForAsyncCondition(page, async id => !(await navigator.locks.query()).held.some(lock =>
    lock.name === `langkraft-audio-download:${id}`), abandoned);
  await triggerCleanup();
  assert.deepEqual(await snapshot(abandoned), { exists: false, chunks: 0 });
  await triggerCleanup();
  assert.deepEqual(await snapshot(location.slice("indexeddb-audio:".length)), { exists: true, chunks: 2 });
  console.log("PASS: cleanup removes abandoned audio and chunks, preserving active owners and complete audio");

  const legacy = await page.evaluate(async () => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("langkraft-audio", 1);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      await new Promise((resolve, reject) => {
        const tx = db.transaction(["audio", "chunks"], "readwrite");
        tx.objectStore("audio").add({ id: "legacy", complete: false, size: 1, count: 1 });
        tx.objectStore("chunks").add({ id: "legacy", index: 0, bytes: new Uint8Array([8]) });
        tx.oncomplete = resolve;
        tx.onabort = () => reject(tx.error);
      });
    } finally { db.close(); }
    return "legacy";
  });
  await triggerCleanup();
  assert.deepEqual(await snapshot(legacy), { exists: true, chunks: 1 });
  console.log("PASS: legacy manifests without a lifetime lock are preserved");

  const fallback = await context.newPage();
  await fallback.addInitScript(() => Object.defineProperty(navigator, "locks", { value: undefined }));
  await fallback.goto(page.url());
  await fallback.waitForFunction(() => !!window.storage);
  const unlocked = await fallback.evaluate(async () => {
    const id = await window.storage.beginAudio("no-lock-support");
    await window.storage.appendAudio(id, new Uint8Array([1]));
    const recovered = await window.storage.recoverIncompleteAudio();
    const location = await window.storage.commitAudio(id, 1);
    const blob = await window.storage.readAudioBlob(location);
    const partial = await window.storage.beginAudio("unlocked-partial");
    await window.storage.appendAudio(partial, new Uint8Array([2]));
    return { recovered, size: blob.size, partial };
  });
  assert.equal(unlocked.recovered, 0);
  assert.equal(unlocked.size, 1);
  await fallback.close();
  await triggerCleanup();
  assert.deepEqual(await snapshot(unlocked.partial), { exists: true, chunks: 1 });
  const recoveredAgain = await page.evaluate(() => window.storage.recoverIncompleteAudio());
  assert.equal(recoveredAgain, 0);
  console.log("PASS: no-lock fallback remains usable and recovery is conservative and idempotent");
  const pendingResult = await page.evaluate(async () => {
    const lease = await window.storage.acquireContentDownload("pending-probe");
    const id = await window.storage.beginAudio("pending-probe");
    await window.storage.appendAudio(id, new Uint8Array([3]));
    const location = await window.storage.commitAudio(id, 1);
    await lease.release();
    let rejected = false;
    try { await window.storage.resolvePendingAudio(location, false); } catch { rejected = true; }
    const before = (await window.storage.listPendingAudio()).filter(entry => entry.location === location);
    const size = (await window.storage.readAudioBlob(location)).size;
    const recoveryLease = await window.storage.acquireContentDownload("pending-probe");
    const removed = await window.storage.resolvePendingAudio(location, false);
    const again = await window.storage.resolvePendingAudio(location, false);
    await recoveryLease.release();
    return { id, rejected, count: before.length, size, removed, again };
  });
  assert.equal(pendingResult.rejected, true);
  assert.equal(pendingResult.count, 1);
  assert.equal(pendingResult.size, 1);
  assert.equal(pendingResult.removed, true);
  assert.equal(pendingResult.again, false);
  assert.deepEqual(await snapshot(pendingResult.id), { exists: false, chunks: 0 });
  console.log("PASS: published pending resolution requires content ownership and removes manifest/chunks idempotently");
  const peer = await context.newPage();
  peer.on('pageerror',error=>errors.push(error.message));
  await peer.goto(page.url());await peer.waitForFunction(()=>!!window.storage);
  await page.evaluate(async()=>{
    window.eventsClient=window.storage.createDatabaseClient();
    await window.eventsClient.request('exec','CREATE TABLE TabProbe(id TEXT PRIMARY KEY,value INTEGER)');
    window.hints=0;window.eventsClient.setChangeListener(()=>{window.hints++;});
  });
  await peer.evaluate(async()=>{
    window.eventsClient=window.storage.createDatabaseClient();
    await window.eventsClient.request('exec','SELECT * FROM TabProbe');
    window.hints=0;window.eventsClient.setChangeListener(()=>{window.hints++;});
    window.isolated=window.storage.createDatabaseClient('recovered-11111111-1111-1111-1111-111111111111');
    window.isolatedHints=0;window.isolated.setChangeListener(()=>{window.isolatedHints++;});
  });
  const sourceHints=await page.evaluate(()=>window.hints);
  await page.evaluate(async()=>{
    await window.eventsClient.request('exec',"INSERT INTO TabProbe VALUES ('A',1)");
    window.eventsClient.publishChanges();
  });
  await peer.waitForFunction(()=>window.hints>0);
  assert.deepEqual(await peer.evaluate(()=>window.eventsClient.request('exec','SELECT * FROM TabProbe')),[['A',1]]);
  assert.equal(await page.evaluate(()=>window.hints),sourceHints,'Received invalidation must not echo');
  assert.equal(await peer.evaluate(()=>window.isolatedHints),0,'Profile channels must be isolated');
  await Promise.all([
    page.evaluate(()=>window.eventsClient.request('exec',"INSERT INTO TabProbe VALUES ('B',2)")),
    peer.evaluate(()=>window.eventsClient.request('exec',"INSERT INTO TabProbe VALUES ('C',3)"))
  ]);
  assert.deepEqual(await page.evaluate(()=>window.eventsClient.request('exec','SELECT id FROM TabProbe ORDER BY id')),[['A'],['B'],['C']]);
  const focused=await peer.evaluate(()=>{
    const before=window.hints;window.dispatchEvent(new Event('focus'));return window.hints-before;
  });
  assert.equal(focused,1);
  const focusFallback=await peer.evaluate(()=>{
    const original=window.BroadcastChannel;window.BroadcastChannel=undefined;
    const client=window.storage.createDatabaseClient();window.BroadcastChannel=original;
    let hints=0;client.setChangeListener(()=>{hints++;});
    window.dispatchEvent(new Event('focus'));client.close();window.dispatchEvent(new Event('focus'));
    return hints;
  });
  assert.equal(focusFallback,1,'Focus fallback works without BroadcastChannel and detaches on close');
  await peer.evaluate(async()=>{
    await window.eventsClient.request('close');window.eventsClient.close();window.isolated.close();
  });
  await peer.close();
  await page.evaluate(async()=>{await window.eventsClient.request('close');window.eventsClient.close();});
  console.log('PASS: profile-scoped invalidation has no echo, concurrent SQLite writes persist, and focus fallback detaches on close');
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
