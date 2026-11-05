import { createDatabaseClient } from "./index.js";
import { isManagedProfile, profileNames } from "./profile.js";
import { acquireProfileLease, withProfileLease, profileStates, PROFILE_REGISTRY } from "./profile-lifecycle.js";
export { removeWebRecoveryProfile } from "./profile-lifecycle.js";

const MAX_BYTES = 64 * 1024 * 1024;
const MAX_STREAM_BYTES = 512 * 1024 * 1024;
const SQLITE_FRAME = 256 * 1024;
const MAX_RECORDS = 50000;
const FORMAT = "langkraft-web-recovery-v1";
const FORMAT_V2 = "langkraft-web-recovery-v2";

// Never open profile databases or interpret presence as validity. Only registry is read.
export async function listWebRecoveryProfiles() {
  if (typeof indexedDB.databases !== "function") {
    throw new Error("This browser cannot list IndexedDB profiles");
  }
  const profiles = new Map();
  const databases = await indexedDB.databases();
  const states = new Map(databases.some(db => db.name === PROFILE_REGISTRY)
    ? (await profileStates()).map(record => [record.profile, record.state]) : []);
  for (const { name } of databases) {
    const match = /^langkraft-(sqlite|audio)-(recovered-.+)$/.exec(name || "");
    if (!match) continue;
    const [, kind, profile] = match;
    try { profileNames(profile); } catch { continue; }
    const state = isManagedProfile(profile) ? states.get(profile) || "unknown" : "legacy";
    if (state === "deleted") continue;
    const entry = profiles.get(profile) || { profile, sqlite: false, audio: false, state };
    entry[kind] = true;
    profiles.set(profile, entry);
  }
  return [...profiles.values()].sort((a, b) => a.profile.localeCompare(b.profile));
}

// Never upgrade or create the source, and never open it through a writable SQL VFS.
async function existingDatabase(name, version, optional = false) {
  return new Promise((resolve, reject) => {
    let missing = false;
    const request = indexedDB.open(name);
    request.onupgradeneeded = () => { missing = true; request.transaction.abort(); };
    request.onerror = () => missing && optional ? resolve(null) : reject(new Error(missing ? `Missing source: ${name}` : request.error.message));
    request.onsuccess = () => {
      const database = request.result;
      if (database.version !== version) { database.close(); reject(new Error(`Unsupported storage version: ${name}`)); }
      else resolve(database);
    };
    request.onblocked = () => reject(new Error("Source database is blocked"));
  });
}

async function snapshot(name, version, stores, optional = false) {
  const database = await existingDatabase(name, version, optional);
  if (!database) return Object.fromEntries(stores.map(store => [store, []]));
  try {
    if (database.objectStoreNames.length !== stores.length || stores.some(store => !database.objectStoreNames.contains(store))) {
      throw new Error("Unsupported storage layout");
    }
    return await new Promise((resolve, reject) => {
      const tx = database.transaction(stores, "readonly");
      const timer = setTimeout(() => { error = new Error("Snapshot timed out; retry when the source is quieter"); tx.abort(); }, 15000);
      const result = {};
      let error;
      let retainedBytes = 0;
      for (const store of stores) {
        const rows = result[store] = [];
        // Keep one consistent readonly transaction, but never materialize the
        // entire store before checking its budget. At most the next record is
        // decoded beyond the retained byte limit (IDB values are indivisible).
        const request = tx.objectStore(store).openCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) return;
          try {
            if (rows.length >= MAX_RECORDS) throw new Error("Recovery record limit exceeded");
            const row = cursor.value;
            const bytes = store === "chunks" ? row.bytes : store === "blocks" ? row.data : undefined;
            if (store === "chunks" && !(bytes instanceof Uint8Array)) throw new Error("Unsupported binary record");
            if (bytes instanceof Uint8Array) {
              if (bytes.byteLength > MAX_BYTES - retainedBytes) throw new Error("Recovery exceeds the 64 MiB in-memory limit");
              retainedBytes += bytes.byteLength;
            }
            rows.push(row);
            cursor.continue();
          } catch (reason) { error = reason; tx.abort(); }
        };
      }
      tx.oncomplete = () => { clearTimeout(timer); resolve(result); };
      tx.onabort = () => { clearTimeout(timer); reject(error || tx.error || new Error("Snapshot aborted")); };
      tx.onerror = () => {};
    });
  } finally { database.close(); }
}

function checkBytes(parts) {
  let total = 0;
  for (const bytes of parts) {
    if (!(bytes instanceof Uint8Array)) throw new Error("Unsupported binary record");
    total += bytes.byteLength;
    if (total > MAX_BYTES) throw new Error("Recovery exceeds the 64 MiB in-memory limit");
  }
}

async function writeNew(name, version, data) {
  const database = await new Promise((resolve, reject) => {
    const request = indexedDB.open(name, version);
    let created = false;
    request.onupgradeneeded = event => {
      if (event.oldVersion !== 0) { request.transaction.abort(); return; }
      created = true;
      if (version === 5) {
        request.result.createObjectStore("blocks", { keyPath: ["path", "offset", "version"] })
          .createIndex("version", ["path", "version"]);
      } else {
        request.result.createObjectStore("audio", { keyPath: "id" });
        request.result.createObjectStore("chunks", { keyPath: ["id", "index"] });
      }
    };
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("Recovery destination is blocked"));
    request.onsuccess = () => {
      if (!created) { request.result.close(); reject(new Error("Recovery destination already exists")); }
      else resolve(request.result);
    };
  });
  try {
    await new Promise((resolve, reject) => {
      const tx = database.transaction(Object.keys(data), "readwrite");
      tx.oncomplete = resolve;
      tx.onabort = () => reject(tx.error || new Error("Recovery write aborted"));
      tx.onerror = () => {};
      try {
        for (const [store, rows] of Object.entries(data)) for (const row of rows) tx.objectStore(store).add(row);
      } catch (error) { tx.abort(); reject(error); }
    });
  } finally { database.close(); }
}

function validAudio(location, audio, chunks) {
  if (typeof location !== "string" || !location.startsWith("indexeddb-audio:")) return null;
  const id = location.slice("indexeddb-audio:".length);
  const entry = audio.get(id);
  const parts = chunks.get(id) || [];
  if (entry?.complete !== true || !Number.isSafeInteger(entry.count) || entry.count <= 0 ||
      !Number.isSafeInteger(entry.size) || entry.size <= 0 || entry.count !== parts.length) return null;
  let size = 0;
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index];
    if (part.index !== index || !(part.bytes instanceof Uint8Array) || !part.bytes.byteLength) return null;
    size += part.bytes.byteLength;
  }
  return size === entry.size ? { entry, parts } : null;
}

async function recover(blocks, media, sourceFile = "langkraft.db") {
  if (!navigator.locks?.request) throw new Error("Recovery requires a browser with Web Locks; source data was preserved");
  if (blocks.length > MAX_RECORDS || media.audio.length > MAX_RECORDS || media.chunks.length > MAX_RECORDS) {
    throw new Error("Recovery record limit exceeded");
  }
  checkBytes(blocks.filter(row => row.data instanceof Uint8Array).map(row => row.data));
  checkBytes(media.chunks.map(row => row.bytes));
  const profile = `recovered-v2-${crypto.randomUUID()}`;
  const names = profileNames(profile);
  const lease = await acquireProfileLease(profile, { create: true });
  let client;
  try {
    const sourcePath = `/${sourceFile}`;
    const renamed = blocks.map(block => {
      if (typeof block.path !== "string") throw new Error("Invalid VFS path");
      return { ...block, path: block.path === sourcePath || block.path.startsWith(sourcePath + "-")
        ? `/${names.file}` + block.path.slice(sourcePath.length) : block.path };
    });
    await writeNew(names.sqlite, 5, { blocks: renamed });
    client = createDatabaseClient(profile);
    const query = (sql, params = []) => client.request("exec", sql, params);
    await query("PRAGMA trusted_schema=OFF");
    const version = (await query("PRAGMA user_version"))[0][0];
    if (!Number.isInteger(version) || version < 1 || version > 3) throw new Error("Unsupported client schema version");
    if ((await query("PRAGMA quick_check"))[0][0] !== "ok") throw new Error("SQLite snapshot is damaged");
    const columns = new Set((await query('PRAGMA table_info("Content")')).map(row => row[1]));
    if (!columns.has("id") || !columns.has("localAudioPath")) throw new Error("Unsupported Content schema");
    const hasStatus = columns.has("downloadStatus");
    const rows = await query(`SELECT id,localAudioPath,${hasStatus ? "downloadStatus" : "'IDLE'"} FROM Content`);
    const audio = new Map(media.audio.map(entry => [entry.id, entry]));
    const chunks = new Map();
    for (const chunk of media.chunks) {
      if (!chunks.has(chunk.id)) chunks.set(chunk.id, []);
      chunks.get(chunk.id).push(chunk);
    }
    for (const parts of chunks.values()) parts.sort((a, b) => a.index - b.index);
    const copied = new Map();
    const content = [];
    await client.request("begin_transaction");
    try {
      const metadata = new Set((await query('PRAGMA table_info("SyncMetadata")')).map(row => row[1]));
      if (metadata.has("key") && metadata.has("value")) {
        await query("DELETE FROM SyncMetadata WHERE key='auth_token' OR instr(key,'audio_download_lock:')=1");
      }
      for (const [id, location, previousStatus] of rows) {
        if (typeof id !== "string" || !id || location !== null && typeof location !== "string" || typeof previousStatus !== "string") {
          throw new Error("Malformed Content values");
        }
        const saved = validAudio(location, audio, chunks);
        const newLocation = saved ? location : null;
        let status = previousStatus;
        if (status === "DOWNLOADING") status = saved ? "COMPLETED" : "ERROR";
        else if (!saved && (location || status === "COMPLETED")) status = "ERROR";
        else if (saved && status === "IDLE") status = "COMPLETED";
        if (saved) copied.set(saved.entry.id, saved);
        if (hasStatus) await query("UPDATE Content SET localAudioPath=?,downloadStatus=? WHERE id=?", [newLocation, status, id]);
        else await query("UPDATE Content SET localAudioPath=? WHERE id=?", [newLocation, id]);
        content.push({ id, previousStatus, status, audio: saved ? "copied" : "unavailable" });
      }
      await client.request("end_transaction");
    } catch (error) { await client.request("rollback_transaction"); throw error; }
    const output = { audio: [], chunks: [] };
    for (const { entry, parts } of copied.values()) {
      // Confirmed snapshot data has no writer/pending ownership in the NEW profile.
      output.audio.push({ id: entry.id, contentId: entry.contentId, count: entry.count, size: entry.size,
        complete: true, lockVersion: 0, pendingVersion: 0, pending: false });
      output.chunks.push(...parts.map(part => ({ id: part.id, index: part.index, bytes: part.bytes })));
    }
    await writeNew(names.audio, 1, output);
    if ((await query("PRAGMA quick_check"))[0][0] !== "ok") throw new Error("Recovered SQLite is damaged");
    await client.request("close");
    return { profile, content, copiedAudio: copied.size, omittedAudio: media.audio.length - copied.size };
  } catch (error) {
    throw new Error(`Recovery failed for ${profile}; no copy link published: ${error.message}`, { cause: error });
  } finally { client?.close(); await lease.release(); }
}

export async function cloneWebProfile(source = "") {
  const names = profileNames(source);
  return withProfileLease(source, async () => {
    const sql = await snapshot(names.sqlite, 5, ["blocks"]);
    const media = await snapshot(names.audio, 1, ["audio", "chunks"], true);
    return recover(sql.blocks, media, names.file);
  });
}

function encode(bytes) {
  let text = "";
  for (let offset = 0; offset < bytes.length; offset += 16384) text += String.fromCharCode(...bytes.subarray(offset, offset + 16384));
  return btoa(text);
}

function decode(text) {
  if (typeof text !== "string" || text.length > MAX_BYTES * 1.4) throw new Error("Invalid archive binary data");
  return Uint8Array.from(atob(text), char => char.charCodeAt(0));
}

function sqliteBlocks(bytes) {
  if (bytes.length < 100 || new TextDecoder().decode(bytes.subarray(0, 16)) !== "SQLite format 3\0") {
    throw new Error("Invalid SQLite snapshot");
  }
  let pageSize = bytes[16] * 256 + bytes[17];
  if (pageSize === 1) pageSize = 65536;
  if (pageSize < 512 || pageSize > 65536 || pageSize & (pageSize - 1) || bytes.length % pageSize) {
    throw new Error("Invalid SQLite page size");
  }
  const blocks = [];
  for (let offset = 0; offset < bytes.length; offset += pageSize) {
    const block = { path: "/langkraft.db", offset: -offset, version: 0, data: bytes.slice(offset, offset + pageSize) };
    if (offset === 0) block.fileSize = bytes.length;
    blocks.push(block);
  }
  return blocks;
}

function appendEncodedFrames(parts, type, bytes, fields = {}) {
  for (let offset = 0; offset < bytes.length; offset += SQLITE_FRAME) {
    parts.push(JSON.stringify({ t: type, ...fields, data: encode(bytes.subarray(offset, offset + SQLITE_FRAME)) }) + "\n");
  }
}

export async function exportWebProfile(source = "") {
  // Always sanitize a NEW copy, even when exporting a previously recovered profile.
  const report = await cloneWebProfile(source);
  return withProfileLease(report.profile, async () => {
    const client = createDatabaseClient(report.profile);
    let sqlite;
    try { sqlite = (await client.request("export_recovery"))[0][0]; await client.request("close"); }
    finally { client.close(); }
    const media = await snapshot(profileNames(report.profile).audio, 1, ["audio", "chunks"]);
    checkBytes([sqlite, ...media.chunks.map(part => part.bytes)]);
    if (sqlite.byteLength + media.chunks.reduce((sum, part) => sum + part.bytes.byteLength, 0) > MAX_STREAM_BYTES) {
      throw new Error("Export exceeds the 512 MiB decoded recovery limit");
    }
    const parts = [
      JSON.stringify({
        format: FORMAT_V2,
        audio: media.audio,
        report
      }) + "\n"
    ];
    appendEncodedFrames(parts, "sqlite", sqlite);
    for (const part of media.chunks) {
      appendEncodedFrames(parts, "chunk", part.bytes, { id: part.id, index: part.index });
    }
    parts.push(JSON.stringify({ t: "end" }) + "\n");
    // Blob from line parts avoids one giant JSON.stringify of the whole archive.
    return { blob: new Blob(parts, { type: "application/x-ndjson" }), report, format: FORMAT_V2 };
  });
}

async function importWebProfileV1(blob) {
  if (blob.size > MAX_BYTES) throw new Error("Archive exceeds the 64 MiB limit");
  const archive = JSON.parse(await blob.text());
  if (archive?.format !== FORMAT || !Array.isArray(archive.audio) || !Array.isArray(archive.chunks) ||
      archive.audio.length > MAX_RECORDS || archive.chunks.length > MAX_RECORDS) throw new Error("Unsupported recovery archive");
  const bytes = decode(archive.sqlite);
  const chunks = archive.chunks.map(part => ({ id: part.id, index: part.index, bytes: decode(part.bytes) }));
  return recover(sqliteBlocks(bytes), { audio: archive.audio, chunks });
}

async function readNdjsonLines(blob, onLine) {
  const reader = blob.stream().pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += value;
    let newline;
    while ((newline = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) await onLine(JSON.parse(line));
    }
  }
  const tail = buffer.trim();
  if (tail) await onLine(JSON.parse(tail));
}

async function importWebProfileV2(blob) {
  if (blob.size > MAX_STREAM_BYTES * 2) throw new Error("Archive exceeds the streaming recovery limit");
  let meta;
  const sqliteParts = [];
  const chunks = [];
  let finished = false;
  let decoded = 0;
  await readNdjsonLines(blob, async line => {
    if (finished) throw new Error("Recovery archive data after end marker");
    if (!meta) {
      if (line.format !== FORMAT_V2 || !Array.isArray(line.audio) || line.audio.length > MAX_RECORDS) {
        throw new Error("Unsupported recovery archive");
      }
      meta = line;
      return;
    }
    if (line.t === "end") { finished = true; return; }
    if (line.t === "sqlite") {
      const bytes = decode(line.data);
      decoded += bytes.byteLength;
      if (decoded > MAX_STREAM_BYTES) throw new Error("Recovery exceeds the 512 MiB decoded limit");
      sqliteParts.push(bytes);
      return;
    }
    if (line.t === "chunk") {
      if (typeof line.id !== "string" || !Number.isInteger(line.index) || line.index < 0) {
        throw new Error("Invalid archive chunk");
      }
      if (chunks.length >= MAX_RECORDS) throw new Error("Recovery record limit exceeded");
      const bytes = decode(line.data);
      decoded += bytes.byteLength;
      if (decoded > MAX_STREAM_BYTES) throw new Error("Recovery exceeds the 512 MiB decoded limit");
      const previous = chunks[chunks.length - 1];
      if (previous && previous.id === line.id && previous.index === line.index) {
        const merged = new Uint8Array(previous.bytes.length + bytes.length);
        merged.set(previous.bytes);
        merged.set(bytes, previous.bytes.length);
        previous.bytes = merged;
      } else {
        chunks.push({ id: line.id, index: line.index, bytes });
      }
      return;
    }
    throw new Error("Unsupported recovery archive record");
  });
  if (!meta || !finished) throw new Error("Incomplete recovery archive");
  const total = sqliteParts.reduce((sum, part) => sum + part.byteLength, 0);
  const sqlite = new Uint8Array(total);
  let offset = 0;
  for (const part of sqliteParts) { sqlite.set(part, offset); offset += part.byteLength; }
  return recover(sqliteBlocks(sqlite), { audio: meta.audio, chunks });
}

export async function importWebProfile(blob) {
  const prefix = await blob.slice(0, 96).text();
  if (prefix.includes(FORMAT_V2)) return importWebProfileV2(blob);
  return importWebProfileV1(blob);
}
