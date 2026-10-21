import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';

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
const content={id:'web-fixture',title:'Deutsch im Alltag',audioUrl:'/api/media/test.wav',localAudioPath:null,
  sourceUrl:'https://youtu.be/video',durationSeconds:4,subtitles:[{id:'cue',contentId:'web-fixture',startMs:0,endMs:4000,originalText:'Hallo Welt',translationText:null}]};
const server=createServer(async(req,res)=>{
  const pathname=new URL(req.url,'http://localhost').pathname;
  if(pathname==='/api/ingest') {
    let body=''; for await(const chunk of req) body+=chunk;
    imports++; const {url}=JSON.parse(body);
    if(url.includes('invalid')) {res.writeHead(400).end('Invalid URL');return;}
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify({jobId:'fixture-job'}));return;
  }
  if(pathname==='/api/ingest/fixture-job') {res.setHeader('Content-Type','application/json');res.end(JSON.stringify({jobId:'fixture-job',url:content.sourceUrl,status:'READY',content}));return;}
  if(pathname==='/api/media/test.wav') {mediaRequests++;res.writeHead(200,{'Content-Type':'audio/wav','Content-Length':audio.length});res.end(audio);return;}
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
try {
  const page=await browser.newPage({viewport:{width:1200,height:800}});
  page.on('pageerror',e=>errors.push(e.message||e.name));
  await page.addInitScript(()=>{
    const original=HTMLMediaElement.prototype.load;
    HTMLMediaElement.prototype.load=function(){window.activeAudio=this;return original.call(this);};
  });
  const origin=`http://127.0.0.1:${server.address().port}`;
  const open=async()=>{await page.goto(origin);await page.waitForFunction(()=>!document.getElementById('startup'));await page.waitForTimeout(1800);await page.addScriptTag({url:origin+'/storage-probe/harness.js'});};
  const query=(sql,params=[])=>page.evaluate(async({sql,params})=>{const client=window.storage.createDatabaseClient();try{return await client.request('exec',sql,params);}finally{client.close();}},{sql,params});
  const waitQuery=async(sql,predicate)=>{const deadline=Date.now()+12000;while(Date.now()<deadline){const rows=await query(sql);if(predicate(rows))return rows;await page.waitForTimeout(100);}throw new Error('Timed out: '+sql);};
  await open();
  await page.screenshot({path:path.join(artifacts,'stats.png')});
  await page.mouse.click(600,775); await page.waitForTimeout(500);
  await page.getByRole('textbox',{name:'YouTube URL'}).fill('https://youtu.be/invalid');await page.waitForTimeout(200);await page.mouse.click(1110,207);
  await page.waitForTimeout(600);assert.equal(imports,1);assert.deepEqual(await query('SELECT id FROM Content'),[]);
  await page.getByRole('textbox',{name:'YouTube URL'}).fill(content.sourceUrl);await page.waitForTimeout(200);await page.mouse.click(1110,207);
  await waitQuery('SELECT id FROM Content',rows=>rows.length===1);assert.equal(imports,2);
  assert.deepEqual(await query('SELECT originalText FROM SubtitleLine'),[['Hallo Welt']]);
  await page.waitForTimeout(300);
  await page.screenshot({path:path.join(artifacts,'library.png')});
  await page.mouse.click(200,380);await page.waitForTimeout(700);
  await page.mouse.click(710,705);await page.waitForFunction(()=>window.activeAudio?.currentTime>0&&!window.activeAudio?.paused,{timeout:10000});
  await page.mouse.click(1170,28);
  const saved=await waitQuery('SELECT localAudioPath,downloadStatus FROM Content',rows=>rows[0]?.[1]==='COMPLETED');
  assert.match(saved[0][0],/^indexeddb-audio:/);
  await page.mouse.click(40,165);await page.waitForTimeout(1000);
  await page.screenshot({path:path.join(artifacts,'word.png')});
  await page.mouse.click(600,615);
  await waitQuery('SELECT word FROM Vocabulary',rows=>rows.length===1);
  const requestsBeforeReload=mediaRequests;
  await open();await page.mouse.click(600,775);await page.waitForTimeout(400);await page.mouse.click(200,380);await page.waitForTimeout(600);
  await page.context().setOffline(true);
  await page.mouse.click(710,705);await page.waitForFunction(()=>window.activeAudio?.currentTime>0&&!window.activeAudio?.paused);
  assert.equal(mediaRequests,requestsBeforeReload,'Offline playback must not fetch remote audio');
  await page.context().setOffline(false);
  assert.equal((await query('SELECT word FROM Vocabulary')).length,1);
  await page.screenshot({path:path.join(artifacts,'offline-player.png')});
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(400);await page.screenshot({path:path.join(artifacts,'mobile.png')});
  assert.deepEqual(errors,[]);
  console.log('PASS: Web import error/retry, async job polling, playback, durable audio, vocabulary and offline reload');
  console.log('PASS: desktop and mobile rendering without runtime errors');
} finally {await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
