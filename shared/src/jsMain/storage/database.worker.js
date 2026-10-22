import SQLiteESMFactory from "wa-sqlite/dist/wa-sqlite-async.mjs";
import * as SQLite from "wa-sqlite";
import { IDBBatchAtomicVFS } from "wa-sqlite/src/examples/IDBBatchAtomicVFS.js";
import { profileNames } from "./profile.js";
import { MemoryVFS } from "wa-sqlite/src/examples/MemoryVFS.js";
import { acquireProfileLease } from "./profile-lifecycle.js";

async function openDatabase(profile) {
  const lease = await acquireProfileLease(profile);
  let sqlite, vfs, database;
  try {
    const wasmUrl = new URL("wa-sqlite/dist/wa-sqlite-async.wasm", import.meta.url).href;
    const module = await SQLiteESMFactory({ locateFile: () => wasmUrl });
    sqlite = SQLite.Factory(module);
    const names = profileNames(profile);
    vfs = new IDBBatchAtomicVFS(names.sqlite, { durability: "strict" });
    sqlite.vfs_register(vfs, true);
    database = await sqlite.open_v2(names.file,
      SQLite.SQLITE_OPEN_READWRITE | SQLite.SQLITE_OPEN_CREATE | SQLite.SQLITE_OPEN_URI);
    await sqlite.exec(database, "PRAGMA foreign_keys=ON; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000");
    return { sqlite, database, vfs, profile, lease };
  } catch (error) {
    try {
      if (database !== undefined) await sqlite.close(database);
      if (vfs) await vfs.close();
      await lease.release();
    } catch {
      // If cleanup fails, retain the lease until the caller terminates this worker.
    }
    throw error;
  }
}

// Capture initialization failures and report them through the request protocol.
let ready;
let queue = Promise.resolve();

async function handle(message) {
  try {
    if (message.action === "initialize") {
      if (ready) throw new Error("Database worker already initialized");
      ready = openDatabase(message.profile).then(value => ({ value }), error => ({ error }));
      return;
    }
    if (!ready) throw new Error("Database worker not initialized");
    const initialized = await ready;
    if (initialized.error) throw initialized.error;
    const { sqlite, database } = initialized.value;
    const statements = {
      begin_transaction: "BEGIN TRANSACTION",
      end_transaction: "COMMIT",
      rollback_transaction: "ROLLBACK"
    };
    let values = [];
    if (message.action === "exec") {
      if (typeof message.sql !== "string") throw new Error("Missing SQL statement");
      for await (const statement of sqlite.statements(database, message.sql)) {
        sqlite.bind_collection(statement, (message.params || []).map(value =>
          ArrayBuffer.isView(value) && !(value instanceof Uint8Array)
            ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : value));
        const columns = sqlite.column_count(statement);
        while (await sqlite.step(statement) === SQLite.SQLITE_ROW) {
          values.push(sqlite.row(statement).map(value => {
            if (typeof value !== "bigint") return value;
            const number = Number(value);
            if (!Number.isSafeInteger(number)) throw new RangeError("SQLite integer exceeds JS precision");
            return number;
          }));
        }
        if (columns === 0) values = [[sqlite.changes(database)]];
      }
    } else if (message.action === "export_recovery") {
      if (!initialized.value.profile) throw new Error("Export requires an isolated recovery copy");
      let memory = initialized.value.exportVfs;
      if (!memory) {
        memory = new MemoryVFS();
        sqlite.vfs_register(memory, false);
        initialized.value.exportVfs = memory;
      }
      try {
        // VACUUM copies live pages only: raw VFS history can retain a deleted token.
        await sqlite.exec(database, "VACUUM INTO 'file:recovery-export.db?vfs=memory'");
        const file = [...memory.mapNameToFile.values()].find(file => file.name.includes("recovery-export.db"));
        if (!file) throw new Error("Recovery export was not created");
        values = [[new Uint8Array(file.data.slice(0, file.size))]];
      } finally {
        memory.close();
        memory.mapNameToFile.clear();
      }
    } else if (statements[message.action]) {
      await sqlite.exec(database, statements[message.action]);
    } else if (message.action === "close") {
      await sqlite.close(database);
      await initialized.value.vfs.close();
      await initialized.value.lease.release();
      initialized.error = new Error("Database connection closed");
    } else {
      throw new Error("Unknown database action");
    }
    // SQLDelight 2.0's wire format uses results.values.
    self.postMessage({ id: message.id, results: { values } });
  } catch (error) {
    self.postMessage({ id: message.id, error: {
      name: error.name || "DatabaseError", message: error.message || String(error)
    } });
  }
}

self.onmessage = event => {
  queue = queue.then(() => handle(event.data));
};
