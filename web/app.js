'use strict';
const $ = id => document.getElementById(id);
let status=null, items=[], mode='link', stopped=false, queue=[], running=false;
const CHUNK=2*1024*1024;
const toast=(t,bad=false)=>{const el=$('toast');el.textContent=t;el.style.background=bad?'#e7a9ac':'';el.classList.remove('hidden');clearTimeout(window.toastTimeout);window.toastTimeout=setTimeout(()=>el.classList.add('hidden'),4500)};
async function api(path, opts={}) {
  const resp=await fetch(path,{credentials:'same-origin',...opts});
  let data;try{data=await resp.json()}catch{data={error:'Unexpected server response'}}
  if(!resp.ok)throw new Error(data.error || `Error ${resp.status}`);
  return data;
}
const post=(url,object)=>api(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(object)});
const bytes=n=>{if(n===0)return '0 B';if(!Number.isFinite(n))return '?';const unit=Math.min(Math.floor(Math.log(n)/Math.log(1024)),4);return (n/1024**unit).toFixed(unit?1:0)+' '+['B','KB','MB','GB','TB'][unit]};
function view(name){document.querySelectorAll('.view').forEach(e=>e.classList.add('hidden'));$(name+'View').classList.remove('hidden');document.querySelectorAll('.nav-item').forEach(e=>e.classList.toggle('selected',e.dataset.view===name));if(name==='inbox')loadItems();}
function authUI(){if(!status)return;const paired=status.authorized;$('pairView').classList.toggle('hidden',paired);$('dashboard').classList.toggle('hidden',!paired);$('dot').classList.toggle('online',paired);$('connectionText').textContent=paired?'Local connection':'Pairing required';$('serverName').textContent=status.name || '';if(paired){$('hostInstructions').classList.toggle('hidden',!status.local);$('mobileInstructions').classList.toggle('hidden',status.local);if(status.local){$('visiblePin').textContent=status.pin;const addresses=(status.addresses||[]).map(ip=>`http://${ip}:${status.port}`);$('addressList').replaceChildren(...addresses.map(a=>{const span=document.createElement('span');span.className='address';span.textContent=a;return span}));if(!addresses.length)$('addressList').textContent='See the local IP printed in the server window';}}}
async function updateStatus(){try{status=await api('/api/status');authUI()}catch(e){$('connectionText').textContent='Host unavailable';$('dot').classList.remove('online')}}
async function loadItems(){if(!status?.authorized)return;try{const data=await api('/api/items');const previous=items[0]?.id;items=data.items;$('count').textContent=items.length;renderItems();if(previous && previous!==items[0]?.id && !document.hidden)toast('New item shared');}catch(e){if(e.message.includes('Pair')){await updateStatus()}else toast(e.message,true)}}
function renderItems(){const root=$('items');root.replaceChildren();if(!items.length){const div=document.createElement('div');div.className='empty';div.innerHTML='<b>No transfers yet</b>Send a link or file and it will appear here.';root.appendChild(div);return}
for(const item of items){const el=document.createElement('div');el.className='item';const icon=document.createElement('div');icon.className='item-icon';icon.textContent=item.kind==='file'?'▣':item.kind==='link'?'↗':'≡';el.appendChild(icon);const body=document.createElement('div');body.className='item-body';const strong=document.createElement('strong');strong.textContent=item.kind==='file'?item.content:(item.kind==='link'?item.content:item.content.split('\n')[0]);strong.title=item.content;const summary=document.createElement('p');summary.textContent=`${item.sender} · ${new Date(item.created*1000).toLocaleString()}${item.kind==='file'?' · '+bytes(item.size):''}`;body.append(strong,summary);el.appendChild(body);const actions=document.createElement('div');actions.className='item-actions';const action=(caption,func)=>{const btn=document.createElement('button');btn.textContent=caption;btn.addEventListener('click',func);actions.appendChild(btn);};
if(item.kind==='file'){const link=document.createElement('a');link.textContent='↓ Save';link.href=`/api/files/${item.id}`;link.setAttribute('download',item.content.split('/').at(-1));actions.appendChild(link)}else{action('Copy',()=>copy(item.content));if(item.kind==='link'){action('↗ Open',()=>{if(/^https?:\/\/[^\s]+$/i.test(item.content))(navigator.userAgent.includes('NerqoAndroid')?window.location.assign(item.content):window.open(item.content,'_blank','noopener,noreferrer'))})}}
if(status.local)action('Delete',async()=>{if(!confirm('Delete this item from the host?'))return;try{await api(`/api/items/${item.id}`,{method:'DELETE'});await loadItems()}catch(e){toast(e.message,true)}});
el.appendChild(actions);root.appendChild(el)} }
async function copy(value){try{if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(value)}else{const t=document.createElement('textarea');t.value=value;t.style.position='fixed';t.style.top='-1000px';document.body.appendChild(t);t.select();if(!document.execCommand('copy'))throw new Error('Copy is unavailable');t.remove()}toast('Copied')}catch{toast('Long press the text to copy',true)}}
function pick(files){if(!files?.length)return;for(const file of files){const name=file.webkitRelativePath || file.name;queue.push({file,name,progress:0,state:'Waiting'});}renderQueue();if(!running)processQueue();}
function key(job){return 'nerqo-transfer:'+job.name+':'+job.file.size+':'+job.file.lastModified}
function renderQueue(){const panel=$('queuePanel');panel.classList.toggle('hidden',!queue.length);$('uploadCounter').textContent=`${queue.filter(j=>j.state==='Complete').length} / ${queue.length} completed`;const root=$('uploadQueue');root.replaceChildren();for(const [index,job] of queue.entries()){const el=document.createElement('div');el.className='queue-item';const info=document.createElement('div');info.className='queue-info';const name=document.createElement('strong');name.textContent=job.name;const state=document.createElement('span');state.className='muted';state.textContent=`${job.state} · ${Math.round(job.progress*100)}%`;info.append(name,state);const track=document.createElement('div');track.className='progress-track';const bar=document.createElement('span');bar.style.width=`${job.progress*100}%`;track.append(bar);el.append(info,track);root.appendChild(el)}}
async function upload(job){let resumeId=null;try{resumeId=localStorage.getItem(key(job))}catch{};let transfer;
try{transfer=await post('/api/transfers/begin',{name:job.name,size:job.file.size,resumeId:resumeId || undefined})}catch(e){if(resumeId && e.message.includes('mismatch')){localStorage.removeItem(key(job));transfer=await post('/api/transfers/begin',{name:job.name,size:job.file.size})}else throw e}
try{localStorage.setItem(key(job),transfer.id)}catch{}
let offset=transfer.offset;while(offset<job.file.size){if(stopped){job.state='Paused';renderQueue();return false}const end=Math.min(offset+CHUNK,job.file.size);const chunk=job.file.slice(offset,end);job.state=`Sending ${bytes(offset)} / ${bytes(job.file.size)}`;renderQueue();const resp=await fetch(`/api/transfers/${transfer.id}/chunk`,{method:'PUT',headers:{'Content-Type':'application/octet-stream','X-Offset':String(offset)},body:chunk});let result;try{result=await resp.json()}catch{throw new Error('Connection interrupted')};if(!resp.ok)throw new Error(result.error || 'Upload error');offset=result.offset;job.progress=offset/job.file.size;renderQueue()}
await post(`/api/transfers/${transfer.id}/finish`,{});try{localStorage.removeItem(key(job))}catch{};job.progress=1;job.state='Complete';renderQueue();return true}
async function processQueue(){if(running)return;running=true;try{for(const job of queue){if(stopped)break;if(job.state==='Complete')continue;try{const done=await upload(job);if(!done)break;}catch(e){job.state='Failed: '+e.message;stopped=true;$('stopQueue').classList.add('hidden');$('resumeQueue').classList.remove('hidden');renderQueue();toast(`${job.name}: ${e.message}`,true);break}}}finally{running=false;if(queue.every(j=>j.state==='Complete')){toast('Files ready on both devices');loadItems();}}}
async function updateCheck(){
  const btn=$('checkUpdates');btn.disabled=true;
  $('updateMessage').textContent='Checking GitHub Releases…';
  try {
    const offer=await api('/api/updates/check');
    if(offer.status==='available'){
      const size=(offer.download_bytes/1048576).toFixed(1), full=(offer.full_bytes/1048576).toFixed(1);
      $('updateMessage').textContent=`Nerqo ${offer.version} • ${offer.mode==='delta'?'Delta':'Full'} update • ${size} MB instead of ${full} MB. ${offer.install_supported?'':'Install from a packaged Windows EXE to enable self-update.'}`;
      $('prepareUpdate').classList.toggle('hidden',!offer.install_supported);
    } else {$('updateMessage').textContent=offer.status==='error'?offer.error:'You are running the latest version.';$('prepareUpdate').classList.add('hidden');}
  } catch(e){$('updateMessage').textContent=e.message}
  finally{btn.disabled=false}
}
async function updatePoll(){
  try{
    const s=await api('/api/updates/status');
    if(s.status==='downloading'){
      const pc=s.total?Math.round(s.received*100/s.total):0;
      $('updateProgress').classList.remove('hidden');$('updateProgressFill').style.width=`${pc}%`;
      $('updateMessage').textContent=`Downloading and verifying update… ${pc}%`;
      setTimeout(updatePoll,750);
    }else if(s.status==='ready'){
      $('updateProgress').classList.add('hidden');$('updateMessage').textContent=`Verified Nerqo ${s.version}. Ready to install.`;
      $('installUpdate').classList.remove('hidden');
    } else if(s.status==='error'){
      $('updateMessage').textContent=s.error;$('updateProgress').classList.add('hidden');
    } else {$('updateMessage').textContent=s.status}
  }catch(e){$('updateMessage').textContent=e.message}
}
function setup(){document.querySelectorAll('.nav-item').forEach(el=>el.addEventListener('click',()=>view(el.dataset.view)));$('seeConnect').addEventListener('click',()=>view('connect'));$('dropZone').addEventListener('click',()=>$('filesInput').click());$('dropZone').addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();$('filesInput').click()}});$('pickFiles').addEventListener('click',()=>$('filesInput').click());$('pickFolder').addEventListener('click',()=>$('folderInput').click());$('filesInput').addEventListener('change',e=>{pick(e.target.files);e.target.value=''});$('folderInput').addEventListener('change',e=>{pick(e.target.files);e.target.value=''});
const drop=$('dropZone');drop.addEventListener('dragover',e=>{e.preventDefault();drop.classList.add('drag')});drop.addEventListener('dragleave',()=>drop.classList.remove('drag'));drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('drag');pick(e.dataTransfer.files)});
document.querySelectorAll('.type-tab').forEach(el=>el.addEventListener('click',()=>{mode=el.dataset.kind;document.querySelectorAll('.type-tab').forEach(b=>{b.classList.toggle('active',b===el);b.setAttribute('aria-selected',String(b===el))});$('noteContent').placeholder=mode==='link'?'https://example.com':'Type anything to share…';$('sendNote').firstChild.textContent=mode==='link'?'Send link ':'Send text '}));$('sendNote').addEventListener('click',async()=>{let content=$('noteContent').value.trim();if(!content)return toast('Write something first',true);const btn=$('sendNote');btn.disabled=true;try{await post('/api/items',{kind:mode,content});$('noteContent').value='';toast('Sent to shared items');await loadItems()}catch(e){toast(e.message,true)}finally{btn.disabled=false}});
$('pairForm').addEventListener('submit',async e=>{e.preventDefault();$('pairStatus').textContent='Connecting…';try{await post('/api/pair',{pin:$('pinInput').value.trim(),name:$('phoneName').value.trim()});$('pairStatus').textContent='';await updateStatus();await loadItems();toast('Device connected')}catch(err){$('pairStatus').textContent=err.message}});
$('stopQueue').addEventListener('click',()=>{stopped=true;$('stopQueue').classList.add('hidden');$('resumeQueue').classList.remove('hidden')});$('resumeQueue').addEventListener('click',()=>{stopped=false;$('resumeQueue').classList.add('hidden');$('stopQueue').classList.remove('hidden');processQueue()});$('refresh').addEventListener('click',loadItems);
$('revoke').addEventListener('click',async()=>{if(!confirm('Revoke access for all phones and change the code?'))return;try{await post('/api/reset',{});await updateStatus();toast('All paired devices disconnected')}catch(e){toast(e.message,true)}});
$('checkUpdates').addEventListener('click',updateCheck);
$('prepareUpdate').addEventListener('click',async()=>{try{await post('/api/updates/prepare',{});$('prepareUpdate').classList.add('hidden');updatePoll()}catch(e){$('updateMessage').textContent=e.message}});
$('installUpdate').addEventListener('click',async()=>{if(!confirm('Install the verified update and restart Nerqo?'))return;try{await post('/api/updates/install',{});$('updateMessage').textContent='Installing update. Nerqo will restart automatically.';$('installUpdate').classList.add('hidden')}catch(e){$('updateMessage').textContent=e.message}});
$('themeToggle').addEventListener('click',()=>{const light=document.documentElement.classList.toggle('light');localStorage.setItem('theme',light?'light':'dark')});try{if(localStorage.getItem('theme')==='light')document.documentElement.classList.add('light')}catch{}
const search=new URLSearchParams(location.search);const draft=search.get('share');if(draft){$('noteContent').value=draft;if(!/^https?:\/\//i.test(draft))document.querySelector('[data-kind="text"]').click()}
}
async function init(){setup();await updateStatus();if(status?.authorized)await loadItems();$('updatePanel').classList.toggle('hidden',!status?.local);if(!status?.local) {$('phoneName').value=/(Android)/i.test(navigator.userAgent)?'Android phone':'Other device'}
setInterval(async()=>{await updateStatus();if(status?.authorized)await loadItems()},4000)}
document.addEventListener('DOMContentLoaded',init);
