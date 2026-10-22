import { isManagedProfile, profileNames } from "./profile.js";

export const PROFILE_REGISTRY = "langkraft-recovery-profiles-v2";
const lockName = profile => `langkraft-profile-lifetime:${profile}`;

async function registry(operation, mode = "readonly") {
  const database = await new Promise((resolve, reject) => {
    const request = indexedDB.open(PROFILE_REGISTRY, 1);
    request.onupgradeneeded = () => request.result.createObjectStore("profiles", { keyPath: "profile" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  try {
    return await new Promise((resolve, reject) => {
      const tx = database.transaction("profiles", mode, mode === "readwrite" ? { durability: "strict" } : {});
      let request;
      try { request = operation(tx.objectStore("profiles")); }
      catch (error) { tx.abort(); reject(error); return; }
      tx.oncomplete = () => resolve(request.result);
      tx.onabort = () => reject(tx.error || new Error("Profile registry transaction aborted"));
      tx.onerror = () => {};
    });
  } finally { database.close(); }
}

export async function profileStates() {
  return registry(store => store.getAll());
}

// Worker lifetime, audio-page lifetime and recovery operations all share this lease.
export async function acquireProfileLease(profile, { create = false } = {}) {
  if (!isManagedProfile(profile)) {
    if (create) throw new Error("New recovery profiles require the v2 lifecycle protocol");
    return { release: async () => {} };
  }
  if (!navigator.locks?.request) throw new Error("Profile access requires Web Locks");
  let ready, failed;
  const granted = new Promise((resolve, reject) => { ready = resolve; failed = reject; });
  const finished = navigator.locks.request(lockName(profile), { mode: "shared", ifAvailable: true }, async lock => {
    if (!lock) throw new Error("Profile is busy being removed; retry later");
    if (create) {
      try { await registry(store => store.add({ profile, state: "active" }), "readwrite"); }
      catch (error) {
        if (error.name === "ConstraintError") throw new Error("Recovery destination already exists");
        throw error;
      }
    } else {
      const record = await registry(store => store.get(profile));
      if (record?.state !== "active") throw new Error("Profile is removed, incomplete deletion or unknown");
    }
    await new Promise(release => ready({ release: async () => { release(); await finished; } }));
  });
  finished.catch(failed);
  return granted;
}

export async function withProfileLease(profile, operation) {
  const lease = await acquireProfileLease(profile);
  try { return await operation(); }
  finally { await lease.release(); }
}

// Clear records rather than queue a non-cancellable deleteDatabase request.
async function clearDatabase(name, version, stores) {
  const database = await new Promise((resolve, reject) => {
    let missing = false;
    let settled = false;
    const request = indexedDB.open(name);
    request.onupgradeneeded = () => { missing = true; request.transaction.abort(); };
    request.onerror = () => { settled = true; missing ? resolve(null) : reject(request.error); };
    request.onblocked = () => { settled = true; reject(new Error("Profile database is blocked")); };
    request.onsuccess = () => {
      if (settled) request.result.close();
      else { settled = true; resolve(request.result); }
    };
  });
  if (!database) return;
  try {
    if (database.version !== version || database.objectStoreNames.length !== stores.length ||
        stores.some(store => !database.objectStoreNames.contains(store))) {
      throw new Error("Unsupported profile storage layout; records were preserved");
    }
    await new Promise((resolve, reject) => {
      const tx = database.transaction(stores, "readwrite", { durability: "strict" });
      tx.oncomplete = resolve;
      tx.onabort = () => reject(tx.error || new Error("Profile clearing aborted; retry removal"));
      tx.onerror = () => {};
      for (const store of stores) tx.objectStore(store).clear();
    });
  } finally { database.close(); }
}

export async function removeWebRecoveryProfile(profile) {
  if (!isManagedProfile(profile)) throw new Error("Only v2 recovery copies can be removed; preserve or clone this source");
  if (!navigator.locks?.request) throw new Error("Profile removal requires Web Locks");
  return navigator.locks.request(lockName(profile), { ifAvailable: true }, async lock => {
    if (!lock) throw new Error("Profile is open or recovery is running; close its tabs and retry");
    const record = await registry(store => store.get(profile));
    if (!record) throw new Error("Unknown recovery profile; records were preserved");
    if (record.state === "deleted") return;
    if (!["active", "deleting"].includes(record.state)) throw new Error("Unknown profile lifecycle state");
    // Commit the tombstone first: partial failure must never reopen a half-cleared copy.
    await registry(store => store.put({ profile, state: "deleting" }), "readwrite");
    const names = profileNames(profile);
    await clearDatabase(names.sqlite, 5, ["blocks"]);
    await clearDatabase(names.audio, 1, ["audio", "chunks"]);
    await registry(store => store.put({ profile, state: "deleted" }), "readwrite");
  });
}
