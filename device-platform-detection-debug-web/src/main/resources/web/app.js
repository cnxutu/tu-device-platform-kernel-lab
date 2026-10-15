'use strict';
const $ = id => document.getElementById(id);
let sessionId, state = {}, busy = false, pollBusy = false, payloadTime = 0;
let aiFeedback = null, feedbackError = '', feedbackGeneration = 0;
const sessionKey = 'optical-debug-session';
function rememberSession(value) {
  try { if(value)sessionStorage.setItem(sessionKey,value);else sessionStorage.removeItem(sessionKey); return true; }
  catch(error) { return false; }
}
function notice(text, error = false) { $('notice').textContent = text; $('notice').className = error ? 'error' : ''; }
async function request(path, body, authorization) {
  const headers = {}; if (sessionId) headers['X-Debug-Session'] = sessionId;
  if (authorization) headers.Authorization = authorization;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {method:body === undefined ? 'GET':'POST',headers,body:body === undefined ? undefined:JSON.stringify(body)});
  const result = await response.json();
  if (!response.ok) {
    if(response.status===401){sessionId=undefined;rememberSession(null);state={};render();throw Error('本地调试会话已失效，服务可能已重启，请填写 Token 重新连接。');}
    throw Error(result.error || '请求失败');
  }
  return result;
}
function number(id) { const input = $(id); if (input.value.trim() === '' || !input.reportValidity()) throw Error('请填写有效的 '+id); const value = Number(input.value); if (!Number.isFinite(value)) throw Error('数值无效'); return value; }
function point() { const value = {longitude:number('longitude'),latitude:number('latitude'),altitude:number('altitude')}; if ($('relative').value.trim() !== '') value.relativeHeight = number('relative'); return value; }
function target() { const value = $('target').value.trim(); if (!/^[A-Za-z0-9_-]{1,64}$/.test(value)) throw Error('目标标识仅支持1～64位字母、数字、下划线或短横线'); return value; }
function points() {
  if (!$('sequence').value.trim()) return [point()];
  return $('sequence').value.trim().split(/\r?\n/).map((line,index) => {
    const columns = line.split(',').map(s=>s.trim());
    if (![3,4].includes(columns.length) || columns.some(s=>!s || !Number.isFinite(Number(s)))) throw Error('第'+(index+1)+'行应为经度,纬度,海拔[,相对高度]');
    const row = {longitude:Number(columns[0]),latitude:Number(columns[1]),altitude:Number(columns[2])}; if (columns.length===4) row.relativeHeight=Number(columns[3]); return row;
  });
}
function render() {
  $('connection').textContent = !sessionId ? '未连接本地会话' : state.platformAuthentication==='REJECTED' ? '平台拒绝认证，请更新 Token' : '已连接 · Token 保存在本地服务';
  $('authorization').placeholder = sessionId ? '服务中已保存 Token；仅更新时填写' : '首次连接或更新时填写 Token';
  const device=state.device;
  if(device) $('environment').textContent = '光电设备 '+device.deviceId+' · 仿真设备 '+state.simulationDeviceNo+' · 登录时快照 '+JSON.stringify({online:device.state===1,trackingEnabled:device.trackingEnabled,guidanceMode:device.guidanceMode,northOffset:device.trackingNorthOffset})+'；当前配置请重新检查前置条件。';
  $('motion-state').textContent=state.motionActive?'动作进行中或停止未确认，请查看记录':'无本工具基础动作';
  $('ai-state').textContent=state.aiTaskId?'本工具仍持有AI任务，结束后请显式关闭':'无本工具活动AI任务';
  $('ai-task').textContent=state.aiTaskId || state.lastAiTaskId || '—';
  renderFeedback();
  $('source-state').textContent=(state.sourceRunning?'连续上报中':'未连续上报')+' · 已发送 '+(state.sentCount||0)+' 点'+(state.targetId?' · 目标 '+state.targetId:'');
  for(const button of document.querySelectorAll('button')) if(button.id!=='login') button.disabled=!sessionId || busy;
  const occupied=Boolean(state.aiTaskId || state.sourceRunning || state.motionActive);
  document.querySelectorAll('[data-move],[data-lens]').forEach(b=>b.disabled=!sessionId||busy||occupied);
  $('ai-start').disabled=!sessionId||busy||occupied;
  $('ai-stop').disabled=!sessionId||!state.aiTaskId;
  $('motion-stop').disabled=!sessionId;
  $('points-stop').disabled=!sessionId;
  $('point-update').disabled=!sessionId||busy||!state.sourceRunning;
  $('points-start').disabled=$('point-once').disabled=!sessionId||busy||occupied;
  renderScene(occupied);
  $('target').disabled=Boolean(state.sourceRunning);
  const lastPoint=[...(state.journal||[])].reverse().find(entry=>entry.type==='POINT');
  if(lastPoint&&lastPoint.time>payloadTime){$('payload').textContent=JSON.stringify(lastPoint.data,null,2);payloadTime=lastPoint.time;}
  const journal=$('journal'); journal.replaceChildren();
  for(const entry of [...(state.journal || [])].reverse()) {
    const row=document.createElement('div');row.className='entry';
    const time=document.createElement('time');time.textContent=new Date(entry.time).toLocaleTimeString('zh-CN',{hour12:false});
    const type=document.createElement('strong');type.textContent=entry.type;
    const message=document.createElement('span');message.textContent=entry.message;
    row.append(time,type,message);
    if(entry.data){const details=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre'); summary.textContent='查看数据';pre.textContent=JSON.stringify(entry.data,null,2);details.append(summary,pre);row.append(details);}
    journal.append(row);
  }
}
function renderScene(occupied) {
  const scene=state.scene||{selected:'upstream',pendingRestore:false,changes:[]};
  $('scene-select').value=scene.selected;
  $('scene-select').disabled=!sessionId||busy||occupied||scene.pendingRestore;
  $('scene-preview').disabled=!sessionId||busy||occupied||scene.pendingRestore||scene.selected!=='pointing';
  $('scene-apply').disabled=!sessionId||busy||occupied||scene.pendingRestore||!(scene.changes||[]).length;
  $('scene-finish').disabled=!sessionId||busy;
  $('scene-recovery').textContent=(scene.pendingRestore?'有待恢复配置：结束上报后需等待平台任务释放，再点击恢复。恢复前不要重启调试台。':'无本工具待恢复配置。')+' '+(scene.note||'全局开关由管理员管理。');
  $('scene-changes').textContent=(scene.changes||[]).length?JSON.stringify(scene.changes,null,2):'尚无配置差异';
  const list=$('scene-checks');list.replaceChildren();
  const pre=scene.preflight;
  if(!pre){list.textContent='尚未检查或配置已变更，请重新检查。';return;}
  const time=document.createElement('p');time.textContent='查询时间：'+new Date(pre.serverTime).toLocaleTimeString('zh-CN',{hour12:false})+' · 瞬时快照';list.append(time);
  for(const item of pre.checks||[]){const row=document.createElement('div');row.className='check '+item.status.toLowerCase();const names={PASS:'通过',BLOCK:'阻塞',WARN:'提示',UNKNOWN:'待确认'};row.textContent=(names[item.status]||'未知')+' · '+item.message;list.append(row);}
}
function renderFeedback() {
  const task=state.lastAiTaskId, badge=$('ai-feedback'), time=$('ai-feedback-time');
  const matches=aiFeedback&&aiFeedback.trackingId===task;
  const available=matches&&!feedbackError;
  const phase=available?aiFeedback.state:'UNKNOWN';
  const names={GUIDING:'等待锁定',LOCKED:'已锁定',LOST:'已丢失',FAILED:'任务失败',STOPPED:'已停止',UNKNOWN:'未知 / 反馈不可用'};
  badge.textContent=task?'最近反馈：'+names[phase]:'未启动';
  badge.className='feedback '+(available?phase.toLowerCase():'unknown');
  time.textContent=matches&&aiFeedback.feedbackTime?'上报时间：'+new Date(aiFeedback.feedbackTime).toLocaleString('zh-CN',{hour12:false})+' · 历史事件，不代表实时状态':'尚无当前任务反馈';
  if(feedbackError&&task)time.textContent+=' · '+feedbackError;
  else if(available&&aiFeedback.reason)time.textContent+=' · '+aiFeedback.reason;
}
async function poll() { if(!sessionId||pollBusy)return; pollBusy=true; const generation=feedbackGeneration; try {
  const next=await request('/api/state');if(generation!==feedbackGeneration)return;state=next;render();
  if(state.lastAiTaskId){try{const result=await request('/api/ai-status');if(generation!==feedbackGeneration)return;aiFeedback=result;feedbackError='';}catch(error){if(generation!==feedbackGeneration)return;feedbackError=error.message;}}
  renderFeedback();
}catch(error){if(generation===feedbackGeneration){feedbackError=error.message;renderFeedback();notice(error.message,true);}}finally{pollBusy=false;} }
async function action(operation, makeBody=()=>({}), priority=false) {
  if(busy&&!priority)return; if(!priority)busy=true;render();
  if(operation==='ai-start'||operation==='ai-stop'){feedbackGeneration++;aiFeedback=null;feedbackError='';renderFeedback();}
  try {const result=await request('/api/'+operation,makeBody());if(operation==='preview'){$('payload').textContent=JSON.stringify(result,null,2);payloadTime=Date.now();$('payload').parentElement.open=true;}else state=result;
    notice(operation==='preview'?'报文仅预览，尚未发送。':'请求已处理，请结合记录与平台视频核对。');
  }catch(error){notice(error.message,true);}finally{if(!priority)busy=false;await poll();render();}
}
$('login').onclick=async()=>{
  try {
    let auth=$('authorization').value.trim();
    if(!auth){
      if(!sessionId){notice('请先填写平台 Token；输入不会随发送操作清除。',true);return;}
      state=await request('/api/state');render();
      if($('zone').value.trim()!==state.zone){notice('修改防区需要填写 Token 重新连接；服务中保存的 Token 未被清除。',true);return;}
      notice(state.platformAuthentication==='REJECTED'?'服务仍保存 Token，但平台拒绝了认证，请填写新的有效 Token。':'已沿用服务中保存的 Token，无需重复填写；摄像头和仿真均未自动启动。',state.platformAuthentication==='REJECTED');return;
    }
    if(!auth.startsWith('Bearer '))auth='Bearer '+auth;
    const result=await request('/api/login',{zone:$('zone').value.trim()},auth);sessionId=result.sessionId;state=result;
    const remembered=rememberSession(sessionId);
    notice(remembered?'连接成功，Token 已保存在本地服务，发送和当前标签页刷新不清除；摄像头和仿真均未自动启动。':'连接成功，但浏览器不允许保存本页会话标记，刷新后需要重新连接；Token 仍在服务内存中。',!remembered);render();
  }catch(error){notice(error.message,true);await poll();}
};
document.querySelectorAll('[data-move]').forEach(button=>button.onclick=()=>action('move',()=>({identifier:'ptz_control',action:Number(button.dataset.move),speed:number('speed'),durationMillis:Math.round(number('duration')*1000)})));
document.querySelectorAll('[data-lens]').forEach(button=>button.onclick=()=>action('move',()=>{const [identifier,direction]=button.dataset.lens.split(':');return {identifier,action:Number(direction),speed:number('speed'),durationMillis:Math.round(number('duration')*1000)};}));
$('motion-stop').onclick=()=>action('motion-stop',undefined,true);
$('ai-start').onclick=()=>action('ai-start',()=>({captureTimeoutSeconds:number('capture'),sessionTimeoutSeconds:number('session')}));
$('ai-stop').onclick=()=>action('ai-stop',undefined,true);
$('preview').onclick=()=>action('preview',()=>({targetId:target(),point:point()}));
$('point-once').onclick=()=>action('point-once',()=>({targetId:target(),point:point()}));
$('point-update').onclick=()=>action('point-update',()=>({point:point()}));
$('points-start').onclick=()=>action('points-start',()=>({targetId:target(),points:points(),intervalMillis:Math.round(number('interval')*1000),durationSeconds:number('source-duration')}));
$('points-stop').onclick=()=>action('points-stop',undefined,true);
$('scene-select').onchange=()=>{const selected=$('scene-select').value;action('scene-select',()=>({scene:selected}));};
$('scene-check').onclick=()=>action('scene-check');
$('scene-preview').onclick=()=>action('scene-preview',()=>({minElevation:number('elevation-min'),maxElevation:number('elevation-max'),windowConfirmed:$('window-confirmed').checked}));
$('scene-apply').onclick=()=>action('scene-apply');
$('scene-finish').onclick=()=>action('scene-finish');
// Fixed simulated points; selection only edits the form and never initiates a request.
const directionSamples={north:['120.0146410','30.2840310','65.3'],east:['120.0151607','30.2835800','65.3'],south:['120.0146410','30.2831290','65.3'],west:['120.0141213','30.2835800','65.3']};
$('sample').onchange=()=>{
  const selection=$('sample').value;
  if(!selection)return;
  const sample=directionSamples[selection]||directionSamples.north;
  ['longitude','latitude','altitude'].forEach((id,index)=>$(id).value=sample[index]);
  $('relative').value='';
  $('sequence').value=selection==='sequence'?Object.values(directionSamples).map(row=>row.join(',')).join('\n'):'';
  if(selection==='sequence'){$('interval').value='15';$('source-duration').value='60';$('sequence').parentElement.open=true;}
  notice('样例已填入，尚未发送。'+(state.sourceRunning?'如需改变正在上报的点位，请点击“更新连续上报点位”；切换序列需先停止上报。':'请预览后再选择发送一次或开始连续上报。'));
};
render();
async function initialize() {
  try {
    let saved;
    try { saved=sessionStorage.getItem(sessionKey); }
    catch(error) { notice('浏览器不允许恢复本页会话标记，请填写 Token 连接；Token 不会保存到浏览器存储。',true); }
    if(saved){
      sessionId=saved;
      state=await request('/api/state');
      $('zone').value=state.zone||'';render();
      notice(state.platformAuthentication==='REJECTED'?'已恢复本地会话，但平台拒绝了已保存的 Token，请更新。':'已恢复本地会话，继续使用服务中保存的 Token；未自动发送指令或上报点位。',state.platformAuthentication==='REJECTED');
    }
    const defaults=await request('/api/defaults');if(!$('zone').value)$('zone').value=defaults.zone||'';
  }catch(error){notice(error.message,true);render();}
}
initialize();
setInterval(poll,1500);
