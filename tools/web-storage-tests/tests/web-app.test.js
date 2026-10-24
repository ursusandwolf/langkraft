import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
import {waitForAsyncCondition} from './async-wait.js';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const bundle = process.env.WEB_APP_BUNDLE || path.join(root, 'webApp/build/kotlin-webpack/js/developmentExecutable');
const resources = path.join(root, 'webApp/build/processedResources/js/main');
const artifacts = path.join(root, 'tools/web-storage-tests/build/app');
const demoSmoke = process.argv.includes('--demo-smoke');
const demo = process.argv.includes('--demo') || demoSmoke;
const noAi = process.argv.includes('--manual');
const aiError = process.argv.includes('--ai-error');
const aiDelayed = process.argv.includes('--ai-delayed');
const manual = noAi || aiError || aiDelayed;
const expectedTranslation = manual ? 'Привет вручную' : 'Hello from remote AI';
await mkdir(artifacts, {recursive:true});
const audio = Buffer.alloc(44 + 16000 * 2 * 4);
audio.write('RIFF'); audio.writeUInt32LE(audio.length-8,4); audio.write('WAVEfmt ',8);
audio.writeUInt32LE(16,16); audio.writeUInt16LE(1,20); audio.writeUInt16LE(1,22);
audio.writeUInt32LE(16000,24); audio.writeUInt32LE(32000,28); audio.writeUInt16LE(2,32); audio.writeUInt16LE(16,34);
audio.write('data',36); audio.writeUInt32LE(audio.length-44,40);
if(demo) {
  // Audible fixture, not recorded speech. A short fade avoids clicks at either end.
  for(let sample=0;sample<16000*4;sample++) {
    const fade=Math.min(1,sample/800,(16000*4-1-sample)/800);
    audio.writeInt16LE(Math.round(4000*fade*Math.sin(2*Math.PI*440*sample/16000)),44+sample*2);
  }
}
let imports=0, mediaRequests=0;
const aiRequests=[];
let holdMedia=false, releaseMedia, onMediaHeld;
const content={id:'web-fixture',title:'Deutsch im Alltag',audioUrl:'/api/media/test.wav',localAudioPath:'/backend-private/downloads/web-fixture/test.wav',
  sourceUrl:'https://youtu.be/video',durationSeconds:4,subtitles:[{id:'cue',contentId:'web-fixture',startMs:0,endMs:4000,originalText:'Hallo Welt',translationText:null}]};
const server=createServer(async(req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
  if(pathname==='/api/ai/capabilities') {
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify({enabled:!noAi,provider:noAi?null:'fixture'}));return;
  }
  if(pathname==='/storage-owner') {res.setHeader('Content-Type','text/html');res.end('<!doctype html><script src="/storage-probe/harness.js"></script>');return;}
  if(pathname==='/api/ingest') {
    let body=''; for await(const chunk of req) body+=chunk;
    imports++; const {url}=JSON.parse(body);
    if(url.includes('invalid')) {res.writeHead(400).end('Invalid URL');return;}
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify({jobId:'fixture-job'}));return;
  }
  if(pathname==='/api/ingest/fixture-job') {res.setHeader('Content-Type','application/json');res.end(JSON.stringify({jobId:'fixture-job',url:content.sourceUrl,status:'READY',content}));return;}
  if(pathname==='/api/media/test.wav') {
    mediaRequests++;
    if(holdMedia) await new Promise(resolve=>{releaseMedia=resolve;onMediaHeld();});
    res.writeHead(200,{'Content-Type':'audio/wav','Content-Length':audio.length});res.end(audio);return;
  }
  if(pathname==='/api/ai/translate-word') {
    let body=''; for await(const chunk of req) body+=chunk;
    aiRequests.push({authorization:req.headers.authorization,body:JSON.parse(body)});
    if(aiError) {res.writeHead(503).end('Provider unavailable');return;}
    if(aiDelayed) await new Promise(resolve=>setTimeout(resolve,2500));
    if(!demo && req.headers.authorization!=='Bearer web-ai-fixture') {res.writeHead(401).end('Unauthorized');return;}
    res.setHeader('Content-Type','application/json');
    const word=aiRequests.at(-1).body.word.toLowerCase();
    const translation=demo ? (word==='welt' ? {translation:'Мир',lemma:'Welt',partOfSpeech:'noun'} :
      {translation:'Привет',lemma:'hallo',partOfSpeech:'interjection'}) :
      {translation:'Hello from remote AI',lemma:'hallo',partOfSpeech:'interjection'};
    res.end(JSON.stringify(translation));return;
  }
  if(pathname.startsWith('/api/')) {res.writeHead(401).end('Unauthorized');return;}
  const relative=pathname==='/'?'index.html':pathname.slice(1);
  const candidates=pathname.startsWith('/storage-probe/') ? [path.join(root,'tools/web-storage-tests/build/tests',pathname.slice('/storage-probe/'.length))] : [path.join(bundle,relative),path.join(resources,relative)];
  for(const candidate of candidates) {
    if(!candidate.startsWith(root)) continue;
    try {const bytes=await readFile(candidate);res.setHeader('Content-Type',candidate.endsWith('.wasm')?'application/wasm':candidate.endsWith('.html')?'text/html':'text/javascript');res.end(bytes);return;}catch{}
  }
  res.writeHead(404).end();
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:!demo,args:['--enable-unsafe-swiftshader',...(demo ? ['--start-maximized'] : [])]});
const errors=[];
let page;
try {
  // Manual sessions must follow the real window size, including after resize.
  // A fixed test viewport can place the bottom navigation below the screen.
  const context=await browser.newContext({viewport:demo ? null : {width:1200,height:800}});
  page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.stack||e.message||e.name));
  await page.addInitScript(demo=>{
    // Reproduce POSIX locale values from a headless Linux environment. The app
    // must normalize these before Compose constructs Intl.Locale instances.
    if(!demo) {
      Object.defineProperty(navigator,'languages',{configurable:true,value:['C','de_DE.UTF-8','en-US']});
      Object.defineProperty(navigator,'language',{configurable:true,value:'C'});
    } else {
      document.addEventListener('DOMContentLoaded',()=>{document.title='Langkraft — тестовое занятие';});
    }
    const original=HTMLMediaElement.prototype.load;
    HTMLMediaElement.prototype.load=function(){window.activeAudio=this;return original.call(this);};
  },demo);
  const origin=`http://127.0.0.1:${server.address().port}`;
  const open=async()=>{await page.goto(origin);await page.waitForFunction(()=>document.documentElement.dataset.appReady==='true',null,{timeout:60000});await page.waitForTimeout(400);if(!demo) await page.addScriptTag({url:origin+'/storage-probe/harness.js'});};
  const query=(sql,params=[])=>page.evaluate(async({sql,params})=>{const client=window.storage.createDatabaseClient();try{return await client.request('exec',sql,params);}finally{client.close();}},{sql,params});
  const waitQuery=async(sql,predicate)=>{const deadline=Date.now()+12000;while(Date.now()<deadline){const rows=await query(sql);if(predicate(rows))return rows;await page.waitForTimeout(100);}throw new Error('Timed out: '+sql);};
  await open();
  if(!demo && !noAi) {
    // Restore a fixture session through the production UserSession startup path.
    await query('INSERT OR REPLACE INTO SyncMetadata(key,value) VALUES (?,?)',['auth_token','web-ai-fixture']);
    await open();
  }
  if(demo) {
    if(demoSmoke) {
      const cdp=await context.newCDPSession(page);
      const {windowId}=await cdp.send('Browser.getWindowForTarget');
      for(const bounds of [{width:1000,height:650},{width:800,height:600}]) {
        await cdp.send('Browser.setWindowBounds',{windowId,bounds:{windowState:'normal'}});
        await cdp.send('Browser.setWindowBounds',{windowId,bounds});
        await page.waitForTimeout(700);
        const size=await page.evaluate(()=>({width:innerWidth,height:innerHeight,outerHeight}));
        assert.ok(size.height<size.outerHeight,'Demo viewport must fit inside the real browser window');
        await page.mouse.click(size.width*0.3,size.height-25); // Stats
        await page.waitForTimeout(300);
        await page.mouse.click(size.width*0.5,size.height-25); // Learn
        await page.getByRole('textbox',{name:'YouTube URL'}).waitFor({state:'visible'});
      }
      assert.deepEqual(errors,[]);
      console.log('PASS: headed demo follows window resize and visible Learn navigation responds to clicks');
    } else {
    console.log(`Тестовое занятие открыто: ${origin}`);
    console.log('Learn → ссылка https://youtu.be/video → IMPORT → материал → PLAY → слово Hallo → ADD TO DICTIONARY → Review.');
    console.log('Аудио — звуковой сигнал, переводы заранее заданы. YouTube и Gemini не вызываются.');
    console.log('Вход не требуется: переводы демонстрационные. Данные сохраняются при перезагрузке; закрытие окна завершит пробное занятие и удалит его временный профиль.');
    await page.waitForEvent('close',{timeout:0});
    }
  } else {
  assert.deepEqual(await page.evaluate(()=>Array.from(navigator.languages)),['de-DE','en-US']);
  assert.equal(await page.evaluate(()=>navigator.language),'de-DE');
  await page.screenshot({path:path.join(artifacts,'stats.png')});
  // Software rendering on fresh Linux runners may paint after the Kotlin startup callback.
  const input=page.getByRole('textbox',{name:'YouTube URL'});
  const navigationDeadline=Date.now()+30000;
  while(!await input.isVisible()) {
    assert.ok(Date.now()<navigationDeadline,'Library did not render');
    await page.mouse.click(600,775);await page.waitForTimeout(500);
  }
  // Observe the actual repository's library SELECT in another Compose application.
  // No storage probe or manual query runs there: a new result proves Flow refreshed.
  const libraryPeer=await context.newPage();
  libraryPeer.on('pageerror',error=>errors.push(error.stack||error.message));
  await libraryPeer.addInitScript(()=>{
    window.libraryPageId=crypto.randomUUID();
    const NativeWorker=window.Worker;
    window.Worker=class extends NativeWorker {
      constructor(...args) {
        super(...args);this.libraryReads=new Set();
        this.addEventListener('message',event=>{
          if(this.libraryReads.delete(event.data.id)&&event.data.results) window.libraryRows=event.data.results.values;
        });
      }
      postMessage(message,...args) {
        if(message.action==='exec'&&/FROM Content ORDER BY createdAt DESC/.test(message.sql||'')) this.libraryReads.add(message.id);
        return super.postMessage(message,...args);
      }
    };
  });
  await libraryPeer.goto(origin);
  await libraryPeer.waitForFunction(()=>document.documentElement.dataset.appReady==='true',null,{timeout:60000});
  const peerDeadline=Date.now()+30000;
  while(!await libraryPeer.getByRole('textbox',{name:'YouTube URL'}).isVisible()) {
    assert.ok(Date.now()<peerDeadline,'Peer library did not render');
    await libraryPeer.mouse.click(600,775);await libraryPeer.waitForTimeout(500);
  }
  await libraryPeer.waitForFunction(()=>Array.isArray(window.libraryRows));
  assert.deepEqual(await libraryPeer.evaluate(()=>window.libraryRows),[]);
  const peerPageId=await libraryPeer.evaluate(()=>window.libraryPageId);
  await page.getByRole('textbox',{name:'YouTube URL'}).fill('https://youtu.be/invalid');await page.waitForTimeout(200);await page.mouse.click(1110,207);
  await page.waitForTimeout(600);assert.equal(imports,1);assert.deepEqual(await query('SELECT id FROM Content'),[]);
  await page.getByRole('textbox',{name:'YouTube URL'}).fill(content.sourceUrl);await page.waitForTimeout(200);await page.mouse.click(1110,207);
  await waitQuery('SELECT id FROM Content',rows=>rows.length===1);assert.equal(imports,2);
  assert.deepEqual(await query('SELECT originalText FROM SubtitleLine'),[['Hallo Welt']]);
  await libraryPeer.waitForFunction(id=>window.libraryRows?.some(row=>row[0]===id),content.id);
  assert.equal(await libraryPeer.evaluate(()=>window.libraryPageId),peerPageId);
  await libraryPeer.screenshot({path:path.join(artifacts,'library-peer.png')});
  await libraryPeer.close();
  // The real backend includes its filesystem path. Reload before any offline
  // download must leave this browser using the remote audio URL.
  await open();
  assert.deepEqual(await query('SELECT localAudioPath,downloadStatus FROM Content WHERE id=?',[content.id]),[[null,'IDLE']],
    'A backend filesystem path must not become a browser offline copy after reload');
  await page.mouse.click(600,775);
  await page.waitForTimeout(300);
  await page.screenshot({path:path.join(artifacts,'library.png')});
  await page.mouse.click(200,380);await page.waitForTimeout(700);
  await page.mouse.click(710,705);await page.waitForFunction(()=>window.activeAudio?.currentTime>0&&!window.activeAudio?.paused,{timeout:10000});
  holdMedia=true;
  const mediaHeld=new Promise(resolve=>{onMediaHeld=resolve;});
  await page.mouse.click(1170,28);
  await Promise.race([mediaHeld,new Promise((_,reject)=>{const timer=setTimeout(()=>reject(new Error('Download did not reach media fixture')),12000);timer.unref();})]);
  assert.deepEqual(await query('SELECT downloadStatus FROM Content WHERE id=?',[content.id]),[['DOWNLOADING']]);
  assert.deepEqual(await query('SELECT value FROM SyncMetadata WHERE key=?',['audio_download_lock:'+content.id]),[['web-lock-v1']]);
  assert.equal(await page.evaluate(id=>window.storage.acquireContentDownload(id),content.id),null,'Real downloader must hold content lease during HTTP');
  holdMedia=false;releaseMedia();releaseMedia=undefined;
  const saved=await waitQuery('SELECT localAudioPath,downloadStatus FROM Content',rows=>rows[0]?.[1]==='COMPLETED');
  assert.match(saved[0][0],/^indexeddb-audio:/);
  const wordResponse=noAi ? null : page.waitForResponse(response=>response.url().endsWith('/api/ai/translate-word'));
  await page.mouse.click(40,165);await page.waitForTimeout(1000);
  const translationInput=page.getByRole('textbox',{name:'Translation',exact:true});
  if(noAi) {
    await page.mouse.click(600,615);
    assert.deepEqual(await query('SELECT word FROM Vocabulary'),[],'An empty translation must not create a card');
  }
  if(aiDelayed) await translationInput.fill(expectedTranslation);
  if(noAi) assert.equal(aiRequests.length,0,'Manual mode must not send AI requests or require sign-in');
  else {
    assert.equal((await wordResponse).status(),aiError?503:200);
    assert.deepEqual(aiRequests[0],{authorization:'Bearer web-ai-fixture',body:{word:'Hallo',context:'Hallo Welt'}});
  }
  if(manual && !aiDelayed) await translationInput.fill(expectedTranslation);
  else await translationInput.waitFor({state:'visible'});
  await page.waitForTimeout(300);
  assert.equal(await translationInput.inputValue(),expectedTranslation,'Late AI response must preserve a manual draft');
  await page.screenshot({path:path.join(artifacts,'word.png')});
  await page.mouse.click(600,615);
  await waitQuery('SELECT word FROM Vocabulary',rows=>rows.length===1);
  assert.deepEqual(await query('SELECT translation,lemma FROM Vocabulary'),[[expectedTranslation,noAi||aiError?null:'hallo']]);
  assert.deepEqual(await page.evaluate(()=>window.storage.listPendingAudio()),[],'Committed download must acknowledge its publication');
  assert.deepEqual(await query("SELECT key FROM SyncMetadata WHERE key LIKE 'audio_download_lock:%'"),[],'Successful download must clear its recovery marker');
  await page.mouse.click(840,775);
  await page.waitForTimeout(700);
  await page.screenshot({path:path.join(artifacts,'review-question.png')});
  await page.mouse.click(600,455);
  await page.waitForTimeout(400);
  await page.screenshot({path:path.join(artifacts,'review-answer.png')});
  // Grade through the real Review screen; SQL probes only observe the result.
  await page.mouse.click(168,495); // AGAIN
  await waitQuery('SELECT lapseCount FROM Vocabulary',rows=>rows[0]?.[0]===1);
  assert.deepEqual(await query('SELECT intervalDays,lapseCount,status FROM Vocabulary'),[[0,1,'LEARNING']]);
  await page.waitForTimeout(400);
  await page.mouse.click(600,455); // Show the answer again after re-learning.
  await page.waitForTimeout(400);
  const submittedAt=Date.now();
  await page.mouse.click(744,495); // GOOD
  const reviewSql='SELECT word,translation,contextSentence,intervalDays,lapseCount,nextReviewAt FROM Vocabulary';
  const reviewed=await waitQuery(reviewSql,rows=>rows[0]?.[3]===1);
  assert.deepEqual(reviewed[0].slice(0,5),['Hallo',expectedTranslation,'Hallo Welt',1,1]);
  assert.ok(reviewed[0][5]>=submittedAt+86400000-1000 && reviewed[0][5]<=Date.now()+86400000+1000,
    'GOOD should schedule the saved word for tomorrow');
  await page.waitForTimeout(400);
  await page.screenshot({path:path.join(artifacts,'review-finished.png')});
  const owner=await context.newPage();
  await owner.goto(origin+'/storage-owner');
  await owner.waitForFunction(()=>!!window.storage);
  const activePublished=await owner.evaluate(async()=>{
    window.contentLease=await window.storage.acquireContentDownload('active-owner');
    const id=await window.storage.beginAudio('active-owner');
    await window.storage.appendAudio(id,new Uint8Array([4]));
    await window.storage.commitAudio(id,1);
    return id;
  });
  for(const id of ['stale-new','active-owner','legacy-owner']) {
    await query("INSERT INTO Content(id,title,audioUrl,sourceUrl,createdAt,downloadStatus) VALUES (?,?,?,?,?,?)",[id,id,content.audioUrl,content.sourceUrl,-1,'DOWNLOADING']);
    if(id!=='legacy-owner') await query('INSERT INTO SyncMetadata(key,value) VALUES (?,?)',['audio_download_lock:'+id,'web-lock-v1']);
  }
  // Simulate a crash during replacement: the old offline path remains valid,
  // but the persisted DOWNLOADING flag must not disable downloads forever.
  await query("UPDATE Content SET downloadStatus='DOWNLOADING' WHERE id=?",[content.id]);
  await query("INSERT INTO SyncMetadata(key,value) VALUES (?,?)",['audio_download_lock:'+content.id,'web-lock-v1']);
  // Leave a partial writer behind without abort; only app startup recovery can
  // remove it after navigation. No beginAudio is called in the new document.
  const abandoned=await page.evaluate(async()=>{
    const id=await window.storage.beginAudio('startup-recovery');
    await window.storage.appendAudio(id,new Uint8Array([7]));
    return id;
  });
  const publishedOrphan=await page.evaluate(async()=>{
    window.orphanLease=await window.storage.acquireContentDownload('published-orphan');
    const id=await window.storage.beginAudio('published-orphan');
    await window.storage.appendAudio(id,new Uint8Array([1,2,3]));
    await window.storage.commitAudio(id,3);
    return id;
  });
  const referencedPublished=await page.evaluate(async()=>{
    window.referenceLease=await window.storage.acquireContentDownload('published-ref');
    const id=await window.storage.beginAudio('published-ref');
    await window.storage.appendAudio(id,new Uint8Array([8]));
    await window.storage.commitAudio(id,1);
    return id;
  });
  await query("INSERT INTO Content(id,title,audioUrl,sourceUrl,createdAt,downloadStatus,localAudioPath) VALUES (?,?,?,?,?,?,?)",
    ['other-ref','Other reference',content.audioUrl,content.sourceUrl,-1,'COMPLETED','indexeddb-audio:'+referencedPublished]);
  const legacyPublished=await page.evaluate(async()=>{
    const id=await window.storage.beginAudio('legacy-published');
    await window.storage.appendAudio(id,new Uint8Array([9]));
    await window.storage.commitAudio(id,1);
    return id;
  });
  const requestsBeforeReload=mediaRequests;
  await open();
  assert.deepEqual(await query(reviewSql),reviewed,'Review progress must survive app reload and reopening SQLite');
  assert.deepEqual(await query('SELECT word FROM Vocabulary WHERE nextReviewAt <= ?',[Date.now()]),[],
    'The reviewed word must not be due immediately after reload');
  assert.deepEqual(await query('SELECT localAudioPath,downloadStatus FROM Content WHERE id=?',[content.id]),[[saved[0][0],'COMPLETED']]);
  assert.deepEqual(await query('SELECT id,downloadStatus FROM Content WHERE id IN (?,?,?) ORDER BY id',['stale-new','active-owner','legacy-owner']),[
    ['active-owner','DOWNLOADING'],['legacy-owner','DOWNLOADING'],['stale-new','ERROR']
  ]);
  assert.equal(await page.evaluate(()=>window.storage.acquireContentDownload('active-owner')),null,'Active owner must exclude both recovery and a second download');
  assert.deepEqual(await audioSnapshot(activePublished),{exists:true,chunks:1});
  assert.deepEqual(await audioSnapshot(referencedPublished),{exists:true,chunks:1});
  assert.deepEqual((await page.evaluate(()=>window.storage.listPendingAudio())).map(entry=>entry.contentId),['active-owner']);
  await query('DELETE FROM Content WHERE id=?',['other-ref']);
  await owner.close();
  await waitForAsyncCondition(page,async()=>!(await navigator.locks.query()).held.some(lock=>
    lock.name==='langkraft-content-download:active-owner'));
  await open();
  assert.deepEqual(await query('SELECT downloadStatus FROM Content WHERE id=?',['active-owner']),[['ERROR']]);
  assert.deepEqual(await query('SELECT downloadStatus FROM Content WHERE id=?',['legacy-owner']),[['DOWNLOADING']]);
  async function audioSnapshot(id) { return page.evaluate(async id=>{
    const db=await new Promise((resolve,reject)=>{
      const request=indexedDB.open('langkraft-audio',1);
      request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);
    });
    try{return await new Promise((resolve,reject)=>{
      const tx=db.transaction(['audio','chunks'],'readonly');
      const manifest=tx.objectStore('audio').get(id);
      const chunks=tx.objectStore('chunks').count(IDBKeyRange.bound([id,0],[id,Infinity]));
      tx.oncomplete=()=>resolve({exists:!!manifest.result,chunks:chunks.result});
      tx.onabort=()=>reject(tx.error);
    });}finally{db.close();}
  },id); }
  assert.deepEqual(await audioSnapshot(abandoned),{exists:false,chunks:0});
  assert.deepEqual(await audioSnapshot(publishedOrphan),{exists:false,chunks:0});
  assert.deepEqual(await audioSnapshot(activePublished),{exists:false,chunks:0});
  assert.deepEqual(await audioSnapshot(referencedPublished),{exists:true,chunks:1},'Confirmed assets are not general retention candidates after unlink');
  assert.deepEqual(await audioSnapshot(legacyPublished),{exists:true,chunks:1});
  assert.deepEqual(await page.evaluate(()=>window.storage.listPendingAudio()),[]);
  await page.mouse.click(600,775);await page.waitForTimeout(400);await page.mouse.click(200,380);await page.waitForTimeout(600);
  await page.context().setOffline(true);
  await page.mouse.click(710,705);await page.waitForFunction(()=>window.activeAudio?.currentTime>0&&!window.activeAudio?.paused);
  assert.equal(mediaRequests,requestsBeforeReload,'Offline playback must not fetch remote audio');
  await page.context().setOffline(false);
  assert.equal((await query('SELECT word FROM Vocabulary')).length,1);
  await page.screenshot({path:path.join(artifacts,'offline-player.png')});
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(400);await page.screenshot({path:path.join(artifacts,'mobile.png')});
  // Exercise the separately bundled recovery UI against a real migrated library.
  const recoveryPage=await context.newPage();
  recoveryPage.on('pageerror',e=>errors.push(e.message));
  await recoveryPage.goto(origin+'/recovery.html');
  await recoveryPage.getByRole('button',{name:'Восстановить из архива'}).click();
  await recoveryPage.waitForFunction(()=>document.getElementById('status').textContent.includes('Выберите архив'));
  await recoveryPage.getByRole('button',{name:'Создать копию',exact:true}).click();
  await recoveryPage.waitForFunction(()=>!document.getElementById('clone').disabled);
  assert.match(await recoveryPage.locator('#status').textContent(),/^Копия готова/);
  const copyLink=recoveryPage.getByRole('link',{name:'Открыть восстановленную копию'});
  await copyLink.waitFor({state:'visible'});
  const copyUrl=await copyLink.getAttribute('href');
  assert.match(copyUrl,/profile=recovered-/);
  const copyPage=await context.newPage();
  copyPage.on('pageerror',e=>errors.push(e.message));
  await copyPage.goto(copyUrl);
  await copyPage.waitForFunction(()=>document.documentElement.dataset.appReady==='true',null,{timeout:60000});
  await copyPage.addScriptTag({url:origin+'/storage-probe/harness.js'});
  const copyData=await copyPage.evaluate(async()=>{
    const client=window.storage.createDatabaseClient();
    try {
      const words=await client.request('exec','SELECT word FROM Vocabulary');
      const auth=await client.request('exec',"SELECT value FROM SyncMetadata WHERE key='auth_token'");
      const paths=await client.request('exec','SELECT localAudioPath FROM Content WHERE localAudioPath IS NOT NULL');
      const blob=await window.storage.readAudioBlob(paths[0][0]);
      return {words,auth,bytes:blob.size};
    } finally {client.close();}
  });
  assert.equal(copyData.words.length,1);assert.deepEqual(copyData.auth,[]);assert.ok(copyData.bytes>0);
  const downloadPromise=recoveryPage.waitForEvent('download');
  await recoveryPage.getByRole('button',{name:'Экспортировать копию'}).click();
  const download=await downloadPromise;
  await recoveryPage.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Копия готова'));
  await recoveryPage.getByLabel('Архив восстановления (.json)').setInputFiles(await download.path());
  await recoveryPage.getByRole('button',{name:'Восстановить из архива'}).click();
  await recoveryPage.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Копия готова'));
  assert.notEqual(await copyLink.getAttribute('href'),copyUrl);
  await recoveryPage.getByRole('button',{name:'Обновить список копий'}).click();
  await recoveryPage.waitForFunction(()=>document.querySelectorAll('#profiles li').length===3);
  assert.equal(await recoveryPage.locator('#profiles a').count(),6);
  const inventoryLink=recoveryPage.locator('#profiles a').filter({hasText:'Восстановить из этой копии'}).first();
  assert.match(await inventoryLink.getAttribute('href'),/recovery\.html\?profile=recovered-/);
  assert.match(await recoveryPage.locator('#profiles-status').textContent(),/Найдено копий: 3/);
  const copyProfile=new URL(copyUrl).searchParams.get('profile');
  const removeCopy=recoveryPage.getByRole('button',{name:`Удалить копию ${copyProfile}`,exact:true});
  recoveryPage.once('dialog',dialog=>dialog.dismiss());
  await removeCopy.click();
  assert.equal(await recoveryPage.locator('#profiles li').count(),3);
  recoveryPage.once('dialog',dialog=>dialog.accept());
  await removeCopy.click();
  await recoveryPage.waitForFunction(()=>!document.getElementById('clone').disabled);
  assert.match(await recoveryPage.locator('#status').textContent(),/Profile is open/);
  await copyPage.close();
  await waitForAsyncCondition(page,async profile=>!(await navigator.locks.query()).held.some(lock=>
    lock.name===`langkraft-profile-lifetime:${profile}`),copyProfile);
  const removalDeadline=Date.now()+10000;
  while(true) {
    recoveryPage.once('dialog',dialog=>dialog.accept());
    await recoveryPage.getByRole('button',{name:`Удалить копию ${copyProfile}`,exact:true}).click();
    await recoveryPage.waitForFunction(()=>!document.getElementById('clone').disabled);
    const removalStatus=await recoveryPage.locator('#status').textContent();
    if(/^Данные копии .* удалены/.test(removalStatus)) break;
    assert.match(removalStatus,/Profile is open/);
    assert.ok(Date.now()<removalDeadline,'Closed profile lease was not released');
    await recoveryPage.waitForTimeout(50);
  }
  await recoveryPage.waitForFunction(()=>document.querySelectorAll('#profiles li').length===2);
  assert.match(await recoveryPage.locator('#status').textContent(),/Данные копии .* удалены/);
  assert.deepEqual(await query('SELECT downloadStatus FROM Content WHERE id=?',['legacy-owner']),[['DOWNLOADING']]);
  await recoveryPage.screenshot({path:path.join(artifacts,'recovery.png')});
  await recoveryPage.close();
  assert.deepEqual(errors,[]);
  console.log('PASS: backend filesystem path is ignored; reload before offline download keeps remote playback');
  console.log('PASS: Web import error/retry, async job polling, playback, durable audio, vocabulary and offline reload');
  console.log(manual ? 'PASS: manual translation reaches vocabulary and Review without a successful AI request' : 'PASS: restored JWT session sends word/context to remote AI; backend translation and lemma reach vocabulary');
  console.log('PASS: lesson reaches Review UI, AGAIN re-learns, GOOD schedules tomorrow and progress survives reload');
  console.log('PASS: desktop and mobile rendering without runtime errors');
  console.log('PASS: app startup removes abandoned audio while preserving saved offline playback');
  console.log('PASS: SQL recovery restores stale downloads, preserves active/legacy owners and recovers a closed owner');
  console.log('PASS: published pending recovery removes orphans, preserves active/legacy/foreign references and confirms bound assets');
  console.log('PASS: recovery page copies/exports/imports a real library; isolated Compose app opens linked audio and SRS without auth');
  }
} catch(error) {if(page) await page.screenshot({path:path.join(artifacts,'failure.png')}).catch(()=>{});console.error('Browser errors:',errors);throw error;} finally {releaseMedia?.();await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
