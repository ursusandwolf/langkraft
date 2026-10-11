/* SQLDelight web-worker protocol; SQL.js snapshots are persisted in IndexedDB. */
importScripts("sql-wasm.js");
let sql, database, storage, inTransaction = false, transactionSnapshot;

function requestResult(request) {
    return new Promise((resolve, reject) => {
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}
function openStorage() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open("langkraft-sqlite-v1", 1);
        request.onupgradeneeded = () => request.result.createObjectStore("snapshot");
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}
function restore(bytes) {
    if (database) database.close();
    database = bytes ? new sql.Database(new Uint8Array(bytes)) : new sql.Database();
    database.run("PRAGMA foreign_keys = ON");
}
function snapshot() {
    const bytes = database.export();
    database.run("PRAGMA foreign_keys = ON");
    return bytes;
}
async function persist() {
    const bytes = snapshot();
    await new Promise((resolve, reject) => {
        const transaction = storage.transaction("snapshot", "readwrite");
        transaction.objectStore("snapshot").put(bytes, "database");
        transaction.oncomplete = resolve;
        transaction.onerror = () => reject(transaction.error);
        transaction.onabort = () => reject(transaction.error || new Error("Database save aborted"));
    });
}
const ready = (async () => {
    sql = await initSqlJs({ locateFile: () => "sql-wasm.wasm" });
    storage = await openStorage();
    const saved = await requestResult(storage.transaction("snapshot").objectStore("snapshot").get("database"));
    restore(saved);
})();
let queue = ready;
self.onmessage = ({data}) => {
    queue = queue.then(async () => {
        let results;
        if (data.action === "begin_transaction") {
            transactionSnapshot = snapshot();
            results = database.exec("BEGIN TRANSACTION");
            inTransaction = true;
        } else if (data.action === "end_transaction") {
            results = database.exec("COMMIT");
            inTransaction = false;
            try { await persist(); } catch (error) { restore(transactionSnapshot); throw error; }
            transactionSnapshot = null;
        } else if (data.action === "rollback_transaction") {
            if (inTransaction) database.exec("ROLLBACK");
            inTransaction = false;
            transactionSnapshot = null;
            results = [];
        } else if (data.action === "exec") {
            if (!data.sql) throw new Error("Missing SQL statement");
            const mutation = !/^\s*(SELECT|PRAGMA|EXPLAIN)\b/i.test(data.sql);
            const before = mutation && !inTransaction ? snapshot() : null;
            results = database.exec(data.sql, data.params)[0] || { values: [] };
            if (mutation && !inTransaction) {
                try { await persist(); } catch (error) { restore(before); throw error; }
            }
        } else {
            throw new Error("Unsupported database action");
        }
        self.postMessage({ id: data.id, results });
    }).catch(error => {
        self.postMessage({ id: data.id, error: String(error.message || error) });
    });
};