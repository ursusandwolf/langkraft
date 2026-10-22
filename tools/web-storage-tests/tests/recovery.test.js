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
    response.end('<!doctype html><script src="/harness.js"></script>'); return;
  }
  const file = path.resolve(directory, "." + pathname);
  if (!file.startsWith(directory + path.sep)) { response.writeHead(404).end(); return; }
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
  page.on("console", message => { if (message.type() === "error") console.log("Browser error:", message.text()); });
  page.on("pageerror", error => errors.push(error.message));
  const origin = `http://127.0.0.1:${server.address().port}`;
  await page.goto(origin);
  await page.waitForFunction(() => !!window.recovery);
  await page.evaluate(async () => {
    const client = window.storage.createDatabaseClient();
    await client.request("exec", `
      PRAGMA user_version=3;
      CREATE TABLE Content(id TEXT PRIMARY KEY,title TEXT,localAudioPath TEXT,downloadStatus TEXT);
      CREATE TABLE Vocabulary(id TEXT PRIMARY KEY,intervalDays INTEGER,nextReviewAt INTEGER,tags TEXT);
      CREATE TABLE SubtitleLine(id TEXT PRIMARY KEY,contentId TEXT,originalText TEXT);
      CREATE TABLE PendingSyncChange(wordId TEXT PRIMARY KEY,changeType TEXT,timestamp INTEGER);
      CREATE TABLE SyncMetadata(key TEXT PRIMARY KEY,value TEXT);
      INSERT INTO Vocabulary VALUES ('word',17,2000000000,'["tag"]');
      INSERT INTO SubtitleLine VALUES ('cue','video','Hallo');
      INSERT INTO PendingSyncChange VALUES ('word','UPSERT',100);
      INSERT INTO SyncMetadata VALUES ('auth_token','PRIVATE-RECOVERY-TOKEN');
      INSERT INTO SyncMetadata VALUES ('sync_cursor','1234');
      INSERT INTO SyncMetadata VALUES ('audio_download_lock:video','legacy');
      INSERT INTO SyncMetadata VALUES ('audioXdownloadYlock:unrelated','keep');
    `);
    const token = await window.storage.beginAudio("video");
    await window.storage.appendAudio(token, new Uint8Array([1, 2, 3]));
    const location = await window.storage.commitAudio(token, 3);
    await client.request("exec", "INSERT INTO Content VALUES ('video','Title',?,'DOWNLOADING'),('other','Other',?,'COMPLETED')", [location, location]);
    await client.request("exec", `INSERT INTO Content VALUES
      ('empty','Empty',NULL,'DOWNLOADING'),('false-complete','Bad',NULL,'COMPLETED'),
      ('external','External','https://example.test/a.mp3','COMPLETED'),
      ('partial','Partial','indexeddb-audio:legacy-part','DOWNLOADING'),
      ('corrupt','Corrupt','indexeddb-audio:corrupt','COMPLETED')`);
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("langkraft-audio"); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    await new Promise((resolve, reject) => {
      const tx = db.transaction(["audio", "chunks"], "readwrite");
      tx.objectStore("audio").add({ id: "legacy-part", complete: false, count: 1, size: 1 });
      tx.objectStore("chunks").add({ id: "legacy-part", index: 0, bytes: new Uint8Array([4]) });
      tx.objectStore("audio").add({ id: "corrupt", complete: true, count: 2, size: 99 });
      tx.objectStore("chunks").add({ id: "corrupt", index: 1, bytes: new Uint8Array([5]) });
      tx.oncomplete = resolve; tx.onabort = () => reject(tx.error);
    }); db.close();
    await client.request("close"); client.close();
    // Keep a SQL writer and a streaming owner active while the read-only snapshot runs.
    window.writer = window.storage.createDatabaseClient();
    await window.writer.request("begin_transaction");
    await window.writer.request("exec", "UPDATE Content SET title='Uncommitted' WHERE id='video'");
    window.activeToken = await window.storage.beginAudio("active");
    await window.storage.appendAudio(window.activeToken, new Uint8Array([9]));
  });
  const report = await page.evaluate(async () => window.recovery.cloneWebProfile());
  assert.equal(report.copiedAudio, 1);
  assert.equal(report.content.find(row => row.id === "video").status, "COMPLETED");
  for (const id of ["empty", "false-complete", "external", "partial", "corrupt"]) {
    assert.equal(report.content.find(row => row.id === id).status, "ERROR");
  }
  const cloned = await context.newPage();
  await cloned.goto(`${origin}/?profile=${report.profile}`);
  await cloned.waitForFunction(() => !!window.recovery);
  const verified = await cloned.evaluate(async () => {
    const client = window.storage.createDatabaseClient();
    const result = {};
    for (const table of ["Content", "Vocabulary", "SubtitleLine", "PendingSyncChange", "SyncMetadata"]) {
      result[table] = await client.request("exec", `SELECT * FROM ${table} ORDER BY 1`);
    }
    const location = result.Content.find(row => row[0] === "video")[2];
    result.audio = [...new Uint8Array(await (await window.storage.readAudioBlob(location)).arrayBuffer())];
    await client.request("close"); client.close(); return result;
  });
  assert.equal(verified.Content.find(row => row[0] === "video")[1], "Title");
  assert.deepEqual(verified.audio, [1, 2, 3]);
  assert.deepEqual(verified.Vocabulary, [["word", 17, 2000000000, '["tag"]']]);
  assert.deepEqual(verified.PendingSyncChange, [["word", "UPSERT", 100]]);
  assert.deepEqual(verified.SubtitleLine, [["cue", "video", "Hallo"]]);
  assert.deepEqual(verified.SyncMetadata, [["audioXdownloadYlock:unrelated", "keep"], ["sync_cursor", "1234"]]);
  await page.evaluate(async () => { window.sourceLease = await window.storage.acquireContentDownload("same-content"); });
  assert.equal(await cloned.evaluate(async () => {
    const lease = await window.storage.acquireContentDownload("same-content");
    if (!lease) return false;
    await lease.release(); return lease.coordinated;
  }), true);
  await page.evaluate(() => window.sourceLease.release());
  console.log("PASS: isolated snapshot with active SQL/audio writers preserves SRS/queue/shared audio and repairs copy only");

  const original = await page.evaluate(async () => {
    const title = await window.writer.request("exec", "SELECT title,downloadStatus FROM Content WHERE id='video'");
    const auth = await window.writer.request("exec", "SELECT value FROM SyncMetadata WHERE key='auth_token'");
    await window.writer.request("rollback_transaction"); await window.writer.request("close"); window.writer.close();
    await window.storage.appendAudio(window.activeToken, new Uint8Array([8]));
    const location = await window.storage.commitAudio(window.activeToken, 2);
    const audio = [...new Uint8Array(await (await window.storage.readAudioBlob(location)).arrayBuffer())];
    return { title, auth, audio };
  });
  assert.deepEqual(original, { title: [["Uncommitted", "DOWNLOADING"]], auth: [["PRIVATE-RECOVERY-TOKEN"]], audio: [9, 8] });
  console.log("PASS: original session, SQL transaction and active download remain usable");

  const exported = await page.evaluate(async () => {
    const { blob } = await window.recovery.exportWebProfile();
    window.archive = blob;
    const archive = JSON.parse(await blob.text());
    return { sqlite: archive.sqlite, size: blob.size, format: archive.format };
  });
  assert.ok(exported.size > 0);
  assert.equal(exported.format, "langkraft-web-recovery-v1");
  assert.equal(Buffer.from(exported.sqlite, "base64").includes(Buffer.from("PRIVATE-RECOVERY-TOKEN")), false);
  const imported = await page.evaluate(async () => window.recovery.importWebProfile(window.archive));
  assert.notEqual(imported.profile, report.profile);
  assert.equal(imported.copiedAudio, 1);
  await cloned.goto(`${origin}/?profile=${imported.profile}`);
  await cloned.waitForFunction(() => !!window.recovery);
  const restored = await cloned.evaluate(async () => {
    const client = window.storage.createDatabaseClient();
    const rows = await client.request("exec", "SELECT localAudioPath,downloadStatus FROM Content WHERE id='video'");
    const words = await client.request("exec", "SELECT * FROM Vocabulary");
    const blob = await window.storage.readAudioBlob(rows[0][0]);
    await client.request("close"); client.close();
    return { status: rows[0][1], words, audio: [...new Uint8Array(await blob.arrayBuffer())] };
  });
  assert.deepEqual(restored, { status: "COMPLETED", words: verified.Vocabulary, audio: [1, 2, 3] });
  console.log("PASS: compact SQLite/audio export removes token history and round-trips into another isolated profile");

  const rejected = await page.evaluate(async () => {
    const bad = [];
    for (const blob of [new Blob(['{}']), new Blob(['{"format":"langkraft-web-recovery-v1","sqlite":"broken","audio":[],"chunks":[]}'])]) {
      try { await window.recovery.importWebProfile(blob); bad.push(false); } catch { bad.push(true); }
    }
    try { await window.recovery.cloneWebProfile("../../default"); bad.push(false); } catch { bad.push(true); }
    const archive = JSON.parse(await window.archive.text());
    const sqlite = Uint8Array.from(atob(archive.sqlite), char => char.charCodeAt(0));
    new DataView(sqlite.buffer).setUint32(60, 99); // user_version in SQLite header.
    let binary = ""; for (const byte of sqlite) binary += String.fromCharCode(byte);
    archive.sqlite = btoa(binary);
    try { await window.recovery.importWebProfile(new Blob([JSON.stringify(archive)])); bad.push(false); } catch { bad.push(true); }
    return bad;
  });
  assert.deepEqual(rejected, [true, true, true, true]);
  console.log("PASS: malformed archives, invalid profile identifiers and future schema are rejected");
  const collision = await page.evaluate(async profile => {
    const uuid = crypto.randomUUID;
    crypto.randomUUID = () => profile.slice(10);
    try { await window.recovery.cloneWebProfile(); return false; }
    catch (error) { return error.message.includes("already exists"); }
    finally { crypto.randomUUID = uuid; }
  }, report.profile);
  assert.equal(collision, true);
  const quota = await page.evaluate(async () => {
    const originalOpen = indexedDB.open;
    indexedDB.open = function(name, ...args) {
      if (name.startsWith('langkraft-audio-recovered-')) throw new DOMException('Test quota exhausted', 'QuotaExceededError');
      return originalOpen.call(this, name, ...args);
    };
    let rejected = false;
    try { await window.recovery.cloneWebProfile(); } catch (error) { rejected = error.message.includes('no copy link published'); }
    finally { indexedDB.open = originalOpen; }
    const client = window.storage.createDatabaseClient();
    try {
      return { rejected, auth: await client.request('exec',"SELECT value FROM SyncMetadata WHERE key='auth_token'"),
        status: await client.request('exec',"SELECT downloadStatus FROM Content WHERE id='video'") };
    } finally {client.close();}
  });
  assert.deepEqual(quota,{rejected:true,auth:[["PRIVATE-RECOVERY-TOKEN"]],status:[["DOWNLOADING"]]});
  const missing = await context.newPage();
  await missing.goto(origin);await missing.waitForFunction(() => !!window.recovery);
  const missingResult = await missing.evaluate(async () => {
    const profile = 'recovered-00000000-0000-0000-0000-000000000000';
    let rejected = false;
    try { await window.recovery.cloneWebProfile(profile); } catch { rejected = true; }
    return { rejected, created: (await indexedDB.databases()).some(db => db.name.includes(profile)) };
  });
  assert.deepEqual(missingResult, { rejected: true, created: false });
  console.log("PASS: existing destination/missing source/quota failure preserve original data and publish no failed copy");
  const legacyContext = await browser.newContext();
  const legacy = await legacyContext.newPage();
  await legacy.goto(origin);await legacy.waitForFunction(() => !!window.recovery);
  const oldSchema = await legacy.evaluate(async () => {
    const original = window.storage.createDatabaseClient();
    await original.request('exec',"PRAGMA user_version=1; CREATE TABLE Content(id TEXT PRIMARY KEY,localAudioPath TEXT); INSERT INTO Content VALUES ('old',NULL)");
    await original.request('close'); original.close();
    const report = await window.recovery.cloneWebProfile();
    const copy = window.storage.createDatabaseClient(report.profile);
    let version, rows;
    try { version=await copy.request('exec','PRAGMA user_version'); rows=await copy.request('exec','SELECT * FROM Content'); }
    finally {copy.close();}
    Object.defineProperty(navigator,'locks',{value:undefined,configurable:true});
    let noLockRejected=false;
    try {await window.recovery.cloneWebProfile();} catch (error) {noLockRejected=error.message.includes('Web Locks');}
    finally {delete navigator.locks;}
    return {version,rows,noLockRejected};
  });
  assert.deepEqual(oldSchema,{version:[[1]],rows:[["old",null]],noLockRejected:true});
  await legacyContext.close();
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
