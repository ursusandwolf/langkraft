const DATABASE = "langkraft-audio";
const PREFIX = "indexeddb-audio:";
let audioDatabase;
const ownerId = crypto.randomUUID();
const activeDownloads = new Map();
const lockName = id => `langkraft-audio-download:${id}`;

// Separate from the asset lock: SQL stays DOWNLOADING before the first chunk
// and after asset publication, until the final SQL link/compensation completes.
export async function acquireContentDownload(contentId) {
  if (!navigator.locks?.request) return { coordinated: false, release: async () => {} };
  let granted;
  let rejected;
  const ready = new Promise((resolve, reject) => { granted = resolve; rejected = reject; });
  const finished = navigator.locks.request(`langkraft-content-download:${contentId}`, { ifAvailable: true }, lock => {
    if (!lock) { granted(null); return; }
    return new Promise(release => granted({ coordinated: true, release: async () => { release(); await finished; } }));
  });
  finished.catch(rejected);
  return ready;
}

async function holdDownload(id) {
  if (!navigator.locks?.request) return { coordinated: false, release() {}, finished: Promise.resolve() };
  let release;
  const held = new Promise(resolve => { release = resolve; });
  let acquired;
  let rejected;
  const ready = new Promise((resolve, reject) => { acquired = resolve; rejected = reject; });
  const finished = navigator.locks.request(lockName(id), () => { acquired(); return held; });
  finished.catch(rejected);
  await ready;
  return { coordinated: true, release, finished };
}

async function releaseDownload(id) {
  const lease = activeDownloads.get(id);
  if (!lease) return;
  activeDownloads.delete(id);
  lease.release();
  await lease.finished;
}

function deleteAudioChunks(tx, id) {
  tx.objectStore("audio").delete(id);
  const cursor = tx.objectStore("chunks").openCursor(IDBKeyRange.bound([id, 0], [id, Infinity]));
  cursor.onsuccess = () => {
    if (cursor.result) { cursor.result.delete(); cursor.result.continue(); }
  };
}

// Only manifests written with the lifetime-lock protocol can be recovered.
// Legacy/no-lock downloads and complete assets require a separate SQL-aware policy.
export async function recoverIncompleteAudio() {
  if (!navigator.locks?.request) return 0;
  const candidates = await transaction("readonly", (tx, done) => {
    const request = tx.objectStore("audio").getAll();
    request.onsuccess = () => done(request.result.filter(entry => !entry.complete && entry.lockVersion === 1));
  });
  let removed = 0;
  for (const candidate of candidates) {
    await navigator.locks.request(lockName(candidate.id), { ifAvailable: true }, async lock => {
      if (!lock) return; // Includes frozen/background tabs; never steal their lock.
      removed += await transaction("readwrite", (tx, done) => {
        const request = tx.objectStore("audio").get(candidate.id);
        request.onsuccess = () => {
          const entry = request.result;
          // Recheck inside the deleting transaction: publication may have won the race.
          if (!entry || entry.complete || entry.lockVersion !== 1) return done(0);
          deleteAudioChunks(tx, entry.id);
          done(1);
        };
      });
    });
  }
  return removed;
}

class LocalAudioUnavailableError extends Error {
  constructor(message) { super(message); this.name = "LocalAudioUnavailableError"; }
}

export function createDatabaseWorker() {
  return new Worker(new URL("./database.worker.js", import.meta.url), { type: "module" });
}

// One message/error listener per worker; every request is removed on completion.
export function createDatabaseClient() {
  const worker = createDatabaseWorker();
  const pending = new Map();
  let sequence = 0;
  let closed = false;
  const close = (error = new Error("Database connection closed")) => {
    if (closed) return;
    closed = true;
    worker.terminate();
    for (const entry of pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    pending.clear();
  };
  worker.onmessage = event => {
    const entry = pending.get(event.data.id);
    if (!entry) return;
    pending.delete(event.data.id);
    clearTimeout(entry.timer);
    if (event.data.error) {
      const error = new Error(event.data.error.message);
      error.name = event.data.error.name;
      entry.reject(error);
    } else entry.resolve(event.data.results.values);
  };
  worker.onerror = event => close(new Error(event.message || "Database worker failed"));
  worker.onmessageerror = () => close(new Error("Invalid database worker message"));
  return {
    request(action, sql, params = []) {
      if (closed) return Promise.reject(new Error("Database connection closed"));
      return new Promise((resolve, reject) => {
        const id = sequence++;
        const timer = setTimeout(() => close(new Error("Database worker timed out")), 30000);
        pending.set(id, { resolve, reject, timer });
        try { worker.postMessage({ id, action, sql, params }); }
        catch (error) { close(error); }
      });
    },
    close() { close(); }
  };
}

function openAudioDatabase() {
  if (!audioDatabase) {
    audioDatabase = new Promise((resolve, reject) => {
      const request = indexedDB.open(DATABASE, 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("audio", { keyPath: "id" });
        request.result.createObjectStore("chunks", { keyPath: ["id", "index"] });
      };
      request.onsuccess = () => {
        const database = request.result;
        database.onversionchange = () => { database.close(); audioDatabase = undefined; };
        resolve(database);
      };
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("Audio storage is blocked by another tab"));
    }).catch(error => { audioDatabase = undefined; throw error; });
  }
  return audioDatabase;
}

async function transaction(mode, operation) {
  const database = await openAudioDatabase();
  return new Promise((resolve, reject) => {
    const tx = database.transaction(["audio", "chunks"], mode);
    let result;
    let error;
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(error || tx.error || new Error("Audio storage transaction aborted"));
    tx.onerror = () => {}; // onabort carries the final error.
    try {
      operation(tx, value => { result = value; }, reason => {
        error = reason;
        tx.abort();
      });
    } catch (reason) { error = reason; tx.abort(); }
  });
}

export async function beginAudio(contentId) {
  await recoverIncompleteAudio();
  const id = crypto.randomUUID();
  const lease = await holdDownload(id);
  activeDownloads.set(id, lease);
  try {
    await transaction("readwrite", tx => {
      tx.objectStore("audio").add({ id, contentId, count: 0, size: 0, complete: false,
        ownerId, updatedAt: Date.now(), lockVersion: lease.coordinated ? 1 : 0 });
    });
  } catch (error) { await releaseDownload(id); throw error; }
  return id;
}

export async function appendAudio(id, bytes) {
  // Copy the Kotlin buffer: it can be reused as soon as this promise resolves.
  const chunk = Uint8Array.from(bytes);
  await transaction("readwrite", (tx, done, fail) => {
    const audio = tx.objectStore("audio");
    const get = audio.get(id);
    get.onsuccess = () => {
      const entry = get.result;
      if (!entry || entry.complete || entry.ownerId !== ownerId || !activeDownloads.has(id)) {
        return fail(new Error("Audio download is not writable by this owner"));
      }
      tx.objectStore("chunks").add({ id, index: entry.count, bytes: chunk });
      entry.count++;
      entry.size += chunk.byteLength;
      entry.updatedAt = Date.now();
      audio.put(entry);
    };
  });
}

export async function commitAudio(id, expectedBytes) {
  const location = await transaction("readwrite", (tx, done, fail) => {
    const audio = tx.objectStore("audio");
    const get = audio.get(id);
    get.onsuccess = () => {
      const entry = get.result;
      if (!entry || entry.complete || !entry.size || entry.ownerId !== ownerId || !activeDownloads.has(id) ||
          (expectedBytes != null && entry.size !== expectedBytes)) {
        return fail(new Error("Audio is empty or incomplete"));
      }
      entry.complete = true;
      entry.updatedAt = Date.now();
      audio.put(entry);
      done(PREFIX + id);
    };
  });
  await releaseDownload(id);
  return location;
}

export async function abortAudio(id) {
  try {
    await transaction("readwrite", (tx, done, fail) => {
      const request = tx.objectStore("audio").get(id);
      request.onsuccess = () => {
        const entry = request.result;
        if (entry && !entry.complete && entry.ownerId && entry.ownerId !== ownerId) {
          return fail(new Error("Audio download belongs to another owner"));
        }
        deleteAudioChunks(tx, id);
      };
    });
  } finally { await releaseDownload(id); }
}

export async function readAudioBlob(location) {
  if (!location.startsWith(PREFIX)) throw new Error("Unknown audio location");
  const id = location.slice(PREFIX.length);
  return transaction("readonly", (tx, done, fail) => {
    const get = tx.objectStore("audio").get(id);
    get.onsuccess = () => {
      const entry = get.result;
      if (!entry?.complete) return fail(new LocalAudioUnavailableError("Saved audio is missing or incomplete"));
      const chunks = [];
      let size = 0;
      const cursor = tx.objectStore("chunks").openCursor(IDBKeyRange.bound([id, 0], [id, Infinity]));
      cursor.onsuccess = () => {
        if (cursor.result) {
          const part = cursor.result.value;
          if (part.index !== chunks.length) return fail(new LocalAudioUnavailableError("Saved audio has a missing chunk"));
          chunks.push(part.bytes);
          size += part.bytes.byteLength;
          cursor.result.continue();
        } else {
          if (size !== entry.size || chunks.length !== entry.count) {
            return fail(new LocalAudioUnavailableError("Saved audio is incomplete"));
          }
          done(new Blob(chunks, { type: "audio/mpeg" }));
        }
      };
    };
  });
}
