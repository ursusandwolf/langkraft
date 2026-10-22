import assert from 'node:assert/strict';
const base=process.env.BACKEND_URL || 'http://127.0.0.1:8080';
const url=process.env.YOUTUBE_URL || 'https://www.youtube.com/watch?v=_NXDM9XXhQ4';
const started=await fetch(base+'/api/ingest',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url})});
assert.equal(started.status,200);
const {jobId}=await started.json();
const deadline=Date.now()+15*60*1000;
let previous;
while(Date.now()<deadline) {
  const response=await fetch(base+'/api/ingest/'+jobId);
  assert.equal(response.status,200);
  const job=await response.json();
  if(previous!==job.status) {console.log(job.status);previous=job.status;}
  if(job.status==='ERROR') throw new Error(job.error);
  if(job.status==='READY') {
    assert.ok(job.content.subtitles.length>0);
    assert.equal(job.content.waveform.length,200);
    assert.ok(Math.max(...job.content.waveform)>0);
    const media=await fetch(base+job.content.audioUrl,{headers:{Range:'bytes=0-255'}});
    assert.equal(media.status,206);
    assert.match(media.headers.get('content-type'),/^audio\//);
    assert.equal((await media.arrayBuffer()).byteLength,256);
    console.log('PASS:',job.content.title,job.content.subtitles.length,'cues, duration',job.content.durationSeconds);
    process.exit(0);
  }
  await new Promise(resolve=>setTimeout(resolve,1000));
}
throw new Error('Import polling timed out');
