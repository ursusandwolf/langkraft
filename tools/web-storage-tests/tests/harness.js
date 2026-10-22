import * as storage from "../../../shared/src/jsMain/storage/index.js";
import * as recovery from "../../../shared/src/jsMain/storage/recovery.js";
window.storage = storage;
window.recovery = recovery;
window.databaseRequest = (worker, action, sql, params = []) => new Promise((resolve, reject) => {
  const id = crypto.randomUUID();
  const listener = event => {
    if (event.data.id !== id) return;
    worker.removeEventListener("message", listener);
    if (event.data.error) reject(new Error(event.data.error.message));
    else resolve(event.data.results.values);
  };
  worker.addEventListener("message", listener);
  worker.postMessage({ id, action, sql, params });
});
