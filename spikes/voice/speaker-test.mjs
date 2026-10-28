// Phone speaker regression: synthetic prompt, then the real microphone; run only through scripts/withphone.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync} from 'node:fs';
const wavPath = process.argv[2], logPath = process.argv[3];
if (!wavPath || !logPath) throw Error('Usage: scripts/withphone node spikes/voice/speaker-test.mjs prompt.wav output.jsonl');
const adb=(...args)=>execFileSync('adb',args,{encoding:'utf8'}).trim();
const pid=adb('shell','pidof','local.agentos.home');
adb('forward','tcp:9223',`localabstract:webview_devtools_remote_${pid}`);
const pages=await(await fetch('http://127.0.0.1:9223/json')).json();
const ws=new WebSocket(pages.find(p=>p.type==='page'&&p.url.includes('agentos.home')).webSocketDebuggerUrl);
await new Promise(r=>ws.onopen=r);
let seq=0; const pending=new Map(), logs=[];
ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){pending.get(m.id)?.(m);pending.delete(m.id);}if(m.method==='Runtime.bindingCalled'){const row=JSON.parse(m.params.payload);logs.push(row);if(row.kind==='dc'&&/^(turn\.(created|done)|error|response|input_audio|output_audio)/.test(row.data.type)) console.log(JSON.stringify(row));}};
const call=(method,params)=>new Promise(r=>{const id=++seq;pending.set(id,r);ws.send(JSON.stringify({id,method,params}));});
const ev=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.result?.exceptionDetails)throw Error(JSON.stringify(r.result.exceptionDetails));return r.result?.result?.value;};
await call('Runtime.enable',{});await call('Runtime.addBinding',{name:'voiceProbe'});
const oldVolume = Number(adb('shell','dumpsys','audio').match(/STREAM_VOICE_CALL:[\s\S]*?2 \(speaker\): (\d+)/)?.[1] ?? 2);
const wav=readFileSync(wavPath).toString('base64');
try {
await ev(`new Promise((ok,no)=>{const end=Date.now()+15000;const i=setInterval(()=>{if(wsOk){clearInterval(i);ok()}else if(Date.now()>end){clearInterval(i);no(Error('agentd offline'))}},100)})`);
console.log(await ev(`(async()=>{voiceStop();voiceDrop(); const t0=performance.now();window.probeLog=(kind,data)=>voiceProbe(JSON.stringify({at:Math.round(performance.now()-t0),kind,data})); const proto=RTCPeerConnection.prototype,create=proto.createDataChannel;proto.createDataChannel=function(...args){const dc=create.apply(this,args);window.probeDC=dc;dc.addEventListener('message',e=>{const m=JSON.parse(e.data);if(!/delta|usage/.test(m.type))probeLog('dc',m)});return dc};await voiceStart();proto.createDataChannel=create;return {settings:micTrack.getSettings()}})()`));
await ev(`new Promise((ok,no)=>{const end=Date.now()+30000;const i=setInterval(()=>{if(probeDC.readyState==='open'){clearInterval(i);ok()}else if(Date.now()>end){clearInterval(i);no(Error('no datachannel'))}},100)})`);
adb('shell','cmd','media_session','volume','--stream','0','--set','5');
console.log(adb('shell','dumpsys','audio').split('\n').filter(l=>/Requested mode|Actual mode|Active communication device/.test(l)).join('\n'));
await ev(`(async()=>{window.probeAC=new AudioContext();const d=probeAC.createMediaStreamDestination(),s=probeAC.createBufferSource();s.buffer=await probeAC.decodeAudioData(Uint8Array.from(atob('${wav}'),c=>c.charCodeAt(0)).buffer);s.connect(d);await pc.getSenders()[0].replaceTrack(d.stream.getAudioTracks()[0]);s.onended=async()=>{await pc.getSenders()[0].replaceTrack(micTrack);probeLog('real_mic',{enabled:micTrack.enabled})};let busy=false;window.probeStats=setInterval(async()=>{if(busy||!pc)return;busy=true;try{for(const r of(await pc.getStats()).values())if(r.type==='inbound-rtp'&&r.kind==='audio'){probeLog('rtp',r);probeLog('ui',{text:document.querySelector('#va').textContent,mic:micTrack?.enabled,top:document.querySelector('.tx').scrollTop,height:document.querySelector('.tx').scrollHeight,client:document.querySelector('.tx').clientHeight})}}finally{busy=false}},500);const an=probeAC.createAnalyser(),buf=new Float32Array(1024);probeAC.createMediaStreamSource(document.querySelector('#spk').srcObject).connect(an);let loud=false,last=0;window.probeMeter=setInterval(()=>{an.getFloatTimeDomainData(buf);const level=Math.sqrt(buf.reduce((s,v)=>s+v*v,0)/buf.length),t=performance.now();if(level>.01){if(!loud){loud=true;probeLog('audio_start',{level})}last=t}else if(loud&&t-last>500){loud=false;probeLog('audio_stop',{})}},20);s.start();probeLog('prompt',{seconds:s.buffer.duration})})()`);
await new Promise(r=>setTimeout(r,65000));
const end=await ev(`({text:document.querySelector('#va').textContent,scroll:{top:document.querySelector('.tx').scrollTop,height:document.querySelector('.tx').scrollHeight,client:document.querySelector('.tx').clientHeight},micEnabled:micTrack?.enabled,settings:micTrack?.getSettings(),state:document.querySelector('#vstate').textContent,spk:{paused:document.querySelector('#spk').paused,muted:document.querySelector('#spk').muted}})`); console.log('END',end);
const output=logs.filter(r=>r.kind==='dc'&&r.data.type==='output_transcript.added');
assert.ok(output.at(-1).at-output[0].at>=30000, 'need at least 30 seconds of assistant speech');
assert.equal(logs.filter(r=>r.kind==='dc'&&r.data.type==='turn.created'&&r.data.turn?.role==='user').length,1,'no speaker echo may create a user turn');
assert.equal(end.text,output.map(r=>r.data.item.text).join(''),'every transcript fragment must be visible');
assert.ok(Math.abs(end.scroll.height-end.scroll.client-end.scroll.top)<2,'newest text must stay visible');
assert.equal(end.micEnabled,true,'listening must resume after the complete reply');
console.log('CONTROLS',await ev(`(async()=>{const speaking=voiceAudio.speaking;document.querySelector('#sorbBtn').click();const first={mic:micTrack.enabled,muted:document.querySelector('#spk').muted};document.querySelector('#sorbBtn').click();const second={mic:micTrack.enabled,muted:document.querySelector('#spk').muted};const oldPC=pc,oldTrack=micTrack;voiceStop();await voiceStart();return {speaking,first,second,warm:pc===oldPC,oldTrack:oldTrack.readyState,newTrack:micTrack.readyState}})()`));
} finally {
await ev(`clearInterval(window.probeStats);clearInterval(window.probeMeter);window.probeAC?.close();close('voice');voiceDrop();app.home()` ).catch(console.error);
adb('shell','cmd','media_session','volume','--stream','0','--set',String(oldVolume));
writeFileSync(logPath,logs.map(x=>JSON.stringify(x)).join('\n')+'\n');ws.close();
}
