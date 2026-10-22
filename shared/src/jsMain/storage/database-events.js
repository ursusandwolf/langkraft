// Invalidation hints only. Never send SQL, tokens, rows or audio between tabs.
export function databaseEvents(profile) {
  let listener;
  let channel;
  let closed = false;
  let scheduled = false;
  try {
    if (typeof BroadcastChannel === "function") {
      channel = new BroadcastChannel(`langkraft-database-events-v1:${profile || "default"}`);
      channel.onmessage = event => {
        if (event.data?.type === "committed") refresh();
      };
    }
  } catch { /* Focus/visibility refresh remains available. */ }
  const refresh = () => { if (!closed) listener?.(); };
  const visible = () => { if (document.visibilityState === "visible") refresh(); };
  globalThis.addEventListener("focus", refresh);
  globalThis.addEventListener("pageshow", refresh);
  document.addEventListener("visibilitychange", visible);
  return {
    setChangeListener(callback) { listener = callback; },
    publishChanges() {
      if (closed || scheduled) return;
      scheduled = true;
      queueMicrotask(() => {
        scheduled = false;
        if (!closed) {
          try { channel?.postMessage({ type: "committed" }); }
          catch { /* A notification failure must not turn a successful commit into an error. */ }
        }
      });
    },
    close() {
      closed = true;
      listener = undefined;
      channel?.close();
      globalThis.removeEventListener("focus", refresh);
      globalThis.removeEventListener("pageshow", refresh);
      document.removeEventListener("visibilitychange", visible);
    }
  };
}
