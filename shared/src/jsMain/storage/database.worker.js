import SQLiteESMFactory from "wa-sqlite/dist/wa-sqlite-async.mjs";
import * as SQLite from "wa-sqlite";
import { IDBBatchAtomicVFS } from "wa-sqlite/src/examples/IDBBatchAtomicVFS.js";

async function openDatabase() {
  const wasmUrl = new URL("wa-sqlite/dist/wa-sqlite-async.wasm", import.meta.url).href;
  const module = await SQLiteESMFactory({ locateFile: () => wasmUrl });
  const sqlite = SQLite.Factory(module);
  const vfs = new IDBBatchAtomicVFS("langkraft-sqlite", { durability: "strict" });
  sqlite.vfs_register(vfs, true);
  const database = await sqlite.open_v2("langkraft.db");
  await sqlite.exec(database, "PRAGMA foreign_keys=ON; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000");
  return { sqlite, database, vfs };
}

// Capture initialization failures and report them through the request protocol.
const ready = openDatabase().then(value => ({ value }), error => ({ error }));
let queue = Promise.resolve();

async function handle(message) {
  try {
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
    } else if (statements[message.action]) {
      await sqlite.exec(database, statements[message.action]);
    } else if (message.action === "close") {
      await sqlite.close(database);
      await initialized.value.vfs.close();
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
