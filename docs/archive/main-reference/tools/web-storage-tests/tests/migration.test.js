import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

// Exercise the actual app startup/factory against a persistent database from version 1.
export async function verifyLegacyMigration(browser, origin) {
  const oldSchema = await readFile(new URL("../../../shared/src/desktopTest/resources/database-v1.sql", import.meta.url), "utf8");
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.route("**/migration-seed", route => route.fulfill({ contentType: "text/html", body: "<!doctype html><title>Migration fixture</title>" }));
    await page.goto(`${origin}/migration-seed`);
    await page.addScriptTag({ url: `${origin}/probe/harness.js` });
    await page.evaluate(async schema => {
      const worker = window.storage.createDatabaseWorker();
      const request = (...args) => window.databaseRequest(worker, ...args);
      try {
        for (const sql of schema.split(";").filter(sql => sql.trim())) await request("exec", sql);
        await request("exec", "INSERT INTO Content VALUES ('legacy','Legacy video','audio',NULL,'source',10,1)");
        await request("exec", "INSERT INTO SubtitleLine VALUES ('z-first','legacy',0,1000,'First',NULL)");
        await request("exec", "INSERT INTO SubtitleLine VALUES ('a-second','legacy',0,1000,'Second',NULL)");
        await request("exec", `INSERT INTO Vocabulary
          (id,word,contentId,subtitleLineId,addedAt,nextReviewAt,intervalDays,easeFactor,status)
          VALUES ('word','First','legacy','z-first',1,100,7,2.7,'LEARNING')`);
        await request("exec", "PRAGMA user_version=1");
      } finally { await request("close"); worker.terminate(); }
    }, oldSchema);
    for (let attempt = 0; attempt < 2; attempt++) {
      // First startup migrates; the second must reopen without applying ALTER again.
      await page.goto(origin);
      await page.getByRole("textbox", { name: "Ссылка на видео YouTube" }).waitFor();
      await page.addScriptTag({ url: `${origin}/probe/harness.js` });
      const saved = await page.evaluate(async () => {
        const worker = window.storage.createDatabaseWorker();
        const request = (...args) => window.databaseRequest(worker, ...args);
        try {
          return {
            version: await request("exec", "PRAGMA user_version"),
            content: await request("exec", "SELECT id,title FROM Content"),
            subtitles: await request("exec", "SELECT id,ordinal FROM SubtitleLine ORDER BY startMs,ordinal,id"),
            words: await request("exec", "SELECT contentId,subtitleLineId,nextReviewAt,intervalDays,easeFactor,status FROM Vocabulary")
          };
        } finally { await request("close"); worker.terminate(); }
      });
      assert.deepEqual(saved.version, [[2]]);
      assert.deepEqual(saved.content, [["legacy", "Legacy video"]]);
      assert.deepEqual(saved.subtitles, [["a-second", 0], ["z-first", 0]]);
      assert.deepEqual(saved.words, [["legacy", "z-first", 100, 7, 2.7, "LEARNING"]]);
    }
    assert.deepEqual(errors, []);
    console.log("PASS: app migrates version 1 and reopens version 2 without losing word links or SRS");
  } finally { await context.close(); }
}
