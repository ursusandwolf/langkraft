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
await mkdir(artifacts, {recursive:true});
const audio = Buffer.alloc(44 + 16000 * 2 * 4);
audio.write('RIFF'); audio.writeUInt32LE(audio.length-8,4); audio.write('WAVEfmt ',8);
audio.writeUInt32LE(16,16); audio.writeUInt16LE(1,20); audio.writeUInt16LE(1,22);
audio.writeUInt32LE(16000,24); audio.writeUInt32LE(32000,28); audio.writeUInt16LE(2,32); audio.writeUInt16LE(16,34);
audio.write('data',36); audio.writeUInt32LE(audio.length-44,40);
let imports=0, mediaRequests=0;
const aiRequests=[];
let holdMedia=false, releaseMedia, onMediaHeld;
const content={id:'web-fixture',title:'Deutsch im Alltag',audioUrl:'/api/media/test.wav',localAudioPath:null,
  sourceUrl:'https://youtu.be/video',durationSeconds:4,subtitles:[{id:'cue',contentId:'web-fixture',startMs:0,endMs:4000,originalText:'Hallo Welt',translationText:null}]};
const server=createServer(async(req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
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
    if(req.headers.authorization!=='Bearer web-ai-fixture') {res.writeHead(401).end('Unauthorized');return;}
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify({translation:'Hello from remote AI',lemma:'hallo',partOfSpeech:'interjection'}));return;
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
const browser=await chromium.launch({headless:true,args:['--enable-unsafe-swiftshader']});
const errors=[];
let page;
try {
  const context=await browser.newContext({viewport:{width:1200,height:800}});
  page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.stack||e.message||e.name));
  await page.addInitScript(()=>{
    // Reproduce POSIX locale values from a headless Linux environment. The app
    // must normalize these before Compose constructs Intl.Locale instances.
    Object.defineProperty(navigator,'languages',{configurable:true,value:['C','de_DE.UTF-8','en-US']});
    Object.defineProperty(navigator,'language',{configurable:true,value:'C'});
    const original=HTMLMediaElement.prototype.load;
    HTMLMediaElement.prototype.load=function(){window.activeAudio=this;return original.call(this);};
  });
  const origin=`http://127.0.0.1:${server.address().port}`;
  const open=async()=>{await page.goto(origin);await page.waitForFunction(()=>document.documentElement.dataset.appReady==='true',null,{timeout:60000});await page.waitForTimeout(400);await page.addScriptTag({url:origin+'/storage-probe/harness.js'});};
  const query=(sql,params=[])=>page.evaluate(async({sql,params})=>{const client=window.storage.createDatabaseClient();try{return await client.request('exec',sql,params);}finally{client.close();}},{sql,params});
  const waitQuery=async(sql,predicate)=>{const deadline=Date.now()+12000;while(Date.now()<deadline){const rows=await query(sql);if(predicate(rows))return rows;await page.waitForTimeout(100);}throw new Error('Timed out: '+sql);};
  await open();
  // Restore a fixture session through the production UserSession startup path.
  await query('INSERT OR REPLACE INTO SyncMetadata(key,value) VALUES (?,?)',['auth_token','web-ai-fixture']);
  await open();
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
  const wordResponse=page.waitForResponse(response=>response.url().endsWith('/api/ai/translate-word'));
  await page.mouse.click(40,165);await page.waitForTimeout(1000);
  assert.equal((await wordResponse).status(),200);
  assert.deepEqual(aiRequests[0],{authorization:'Bearer web-ai-fixture',body:{word:'Hallo',context:'Hallo Welt'}});
  await page.screenshot({path:path.join(artifacts,'word.png')});
  await page.mouse.click(600,615);
  await waitQuery('SELECT word FROM Vocabulary',rows=>rows.length===1);
  assert.deepEqual(await query('SELECT translation,lemma FROM Vocabulary'),[['Hello from remote AI','hallo']]);
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
  assert.deepEqual(reviewed[0].slice(0,5),['Hallo','Hello from remote AI','Hallo Welt',1,1]);
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
  console.log('PASS: Web import error/retry, async job polling, playback, durable audio, vocabulary and offline reload');
  console.log('PASS: restored JWT session sends word/context to remote AI; backend translation and lemma reach vocabulary');
  console.log('PASS: lesson reaches Review UI, AGAIN re-learns, GOOD schedules tomorrow and progress survives reload');
  console.log('PASS: desktop and mobile rendering without runtime errors');
  console.log('PASS: app startup removes abandoned audio while preserving saved offline playback');
  console.log('PASS: SQL recovery restores stale downloads, preserves active/legacy owners and recovers a closed owner');
  console.log('PASS: published pending recovery removes orphans, preserves active/legacy/foreign references and confirms bound assets');
  console.log('PASS: recovery page copies/exports/imports a real library; isolated Compose app opens linked audio and SRS without auth');
} catch(error) {if(page) await page.screenshot({path:path.join(artifacts,'failure.png')}).catch(()=>{});console.error('Browser errors:',errors);throw error;} finally {releaseMedia?.();await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
