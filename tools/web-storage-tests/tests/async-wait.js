// Playwright's waitForFunction tests the Promise object for truthiness rather
// than polling its eventual boolean. Await each browser evaluation in Node.
export async function waitForAsyncCondition(page, predicate, argument, timeout = 10000) {
  const deadline = Date.now() + timeout;
  while (true) {
    if (await page.evaluate(predicate, argument)) return;
    if (Date.now() >= deadline) throw new Error("Timed out waiting for asynchronous browser condition");
    await page.waitForTimeout(50);
  }
}
