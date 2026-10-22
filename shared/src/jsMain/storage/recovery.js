import { createDatabaseClient } from "./index.js";
import { profileNames } from "./profile.js";

const MAX_BYTES = 64 * 1024 * 1024;
const MAX_RECORDS = 50000;
const FORMAT = "langkraft-web-recovery-v1";

// Inventory only: never open/upgrade a database or interpret presence as validity.
export async function listWebRecoveryProfiles() {
  if (typeof indexedDB.databases !== "function") {
    throw new Error("This browser cannot list IndexedDB profiles");
  }
  const profiles = new Map();
  for (const { name } of await indexedDB.databases()) {
    const match = /^langkraft-(sqlite|audio)-(recovered-.+)$/.exec(name || "");
    if (!match) continue;
    const [, kind, profile] = match;
    try { profileNames(profile); } catch { continue; }
    const entry = profiles.get(profile) || { profile, sqlite: false, audio: false };
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
      for (const store of stores) {
        const request = tx.objectStore(store).getAll(undefined, MAX_RECORDS + 1);
        request.onsuccess = () => {
          if (request.result.length > MAX_RECORDS) { error = new Error("Recovery record limit exceeded"); tx.abort(); }
          else result[store] = request.result;
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
  const profile = `recovered-${crypto.randomUUID()}`;
  const names = profileNames(profile);
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
  } finally { client?.close(); }
}

export async function cloneWebProfile(source = "") {
  const names = profileNames(source);
  const sql = await snapshot(names.sqlite, 5, ["blocks"]);
  const media = await snapshot(names.audio, 1, ["audio", "chunks"], true);
  return recover(sql.blocks, media, names.file);
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

export async function exportWebProfile(source = "") {
  // Always sanitize a NEW copy, even when exporting a previously recovered profile.
  const report = await cloneWebProfile(source);
  const client = createDatabaseClient(report.profile);
  let sqlite;
  try { sqlite = (await client.request("export_recovery"))[0][0]; await client.request("close"); }
  finally { client.close(); }
  const media = await snapshot(profileNames(report.profile).audio, 1, ["audio", "chunks"]);
  const archive = { format: FORMAT, sqlite: encode(sqlite), audio: media.audio,
    chunks: media.chunks.map(part => ({ ...part, bytes: encode(part.bytes) })), report };
  const blob = new Blob([JSON.stringify(archive)], { type: "application/json" });
  if (blob.size > MAX_BYTES) throw new Error("Export exceeds the 64 MiB archive limit");
  return { blob, report };
}

export async function importWebProfile(blob) {
  if (blob.size > MAX_BYTES) throw new Error("Archive exceeds the 64 MiB limit");
  const archive = JSON.parse(await blob.text());
  if (archive?.format !== FORMAT || !Array.isArray(archive.audio) || !Array.isArray(archive.chunks) ||
      archive.audio.length > MAX_RECORDS || archive.chunks.length > MAX_RECORDS) throw new Error("Unsupported recovery archive");
  const bytes = decode(archive.sqlite);
  if (bytes.length < 100 || new TextDecoder().decode(bytes.subarray(0, 16)) !== "SQLite format 3\0") throw new Error("Invalid SQLite snapshot");
  let pageSize = bytes[16] * 256 + bytes[17];
  if (pageSize === 1) pageSize = 65536;
  if (pageSize < 512 || pageSize > 65536 || pageSize & (pageSize - 1) || bytes.length % pageSize) throw new Error("Invalid SQLite page size");
  const blocks = [];
  for (let offset = 0; offset < bytes.length; offset += pageSize) {
    const block = { path: "/langkraft.db", offset: -offset, version: 0, data: bytes.slice(offset, offset + pageSize) };
    if (offset === 0) block.fileSize = bytes.length;
    blocks.push(block);
  }
  const chunks = archive.chunks.map(part => ({ id: part.id, index: part.index, bytes: decode(part.bytes) }));
  return recover(blocks, { audio: archive.audio, chunks });
}
