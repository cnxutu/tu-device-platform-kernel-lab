'use strict';
const $ = id => document.getElementById(id);
let catalog, state = {connected:false, online:false}, cursor = 0, history = [], busy = false, pollFlight;
const cards = new Map(), propertyCells = new Map();
const time = value => value ? new Date(value).toLocaleTimeString('zh-CN', {hour12:false}) : '—';
const element = (tag, text, className) => { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node; };

async function request(path, body) {
  const response = await fetch(path, body === undefined ? {} : {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '请求失败');
  return data;
}
function notice(message, error = false) { $('notice').textContent = message; $('notice').className = error ? 'error' : ''; }
async function action(path, body, message, priority = false) {
  if (busy && !priority) return;
  if (!priority) busy = true;
  updateControls();
  try { await request(path, body); notice(message); await poll(); }
  catch (error) { notice(error.message, true); }
  finally { if (!priority) busy = false; updateControls(); }
}
function numericInput(label, min, max, value) {
  const wrapper = element('label', label), input = element('input'); input.type = 'number'; input.min = min; input.max = max; input.value = value; input.step = 1; wrapper.append(input); return {wrapper, input};
}
function renderCatalog() {
  for (const service of catalog.services) {
    const card = element('article', undefined, 'card service' + (service.identifier === 'ptz_control' ? ' ptz' : ''));
    card.append(element('h3', service.name), element('code', service.identifier));
    const parameters = element('div', undefined, 'parameters');
    const speedSpec = service.inputParams.find(input => input.identifier === 'speedPercent');
    const speed = speedSpec ? numericInput('速度 speedPercent / %', Number(speedSpec.dataSpecs.min), Number(speedSpec.dataSpecs.max), 30) : null;
    const duration = numericInput('持续时间 / 毫秒', 100, 10000, 1000);
    if (speed) parameters.append(speed.wrapper); parameters.append(duration.wrapper); card.append(parameters);
    const actions = element('div', undefined, 'actions');
    const actionSpec = service.inputParams.find(input => input.identifier === 'action');
    const definitions = actionSpec.dataSpecsList.filter(item => Number(item.value) !== 0);
    const ptzLayout = [5,3,7,1,null,2,6,4,8];
    if (service.identifier === 'ptz_control') definitions.sort((a,b) => ptzLayout.indexOf(Number(a.value)) - ptzLayout.indexOf(Number(b.value)));
    for (const definition of definitions) {
      const button = element('button', definition.name); button.type = 'button'; button.className = 'move';
      if (service.identifier === 'ptz_control') {
        const position = ptzLayout.indexOf(Number(definition.value));
        button.style.gridRow = String(Math.floor(position / 3) + 1);
        button.style.gridColumn = String(position % 3 + 1);
      }
      button.addEventListener('click', () => {
        if (!duration.input.reportValidity() || (speed && !speed.input.reportValidity())) return;
        const params = {channelId:'1', action:Number(definition.value)}; if (speed) params.speedPercent = Number(speed.input.value);
        action('/api/invoke', {identifier:service.identifier, params, durationMillis:Number(duration.input.value)}, definition.name + '已写入 TCP，后台已安排停止。');
      }); actions.append(button);
    }
    card.append(actions);
    const stop = element('button', '停止' + (service.identifier === 'ptz_control' ? '云台' : service.identifier === 'zoom_control' ? '变倍' : '聚焦'), 'scope-stop');
    stop.addEventListener('click', () => action('/api/invoke', {identifier:service.identifier, params:{channelId:'1', action:0}}, '停止指令已写入 TCP。', true));
    card.append(stop, element('div', '逻辑控制通道 channelId = 1' + (speed ? '' : ' · 固定聚焦速度'), 'model-hint'));
    $('services').append(card); cards.set(service.identifier, {card, speed, duration});
  }
  for (const property of catalog.properties) {
    const cell = element('div', undefined, 'property'); const value = element('div', '未上报', 'property-value absent'), received = element('div', '最近接收 —', 'property-time');
    cell.append(element('div', property.name, 'property-label'), value, element('code', property.identifier, 'property-key'), received); $('properties').append(cell);
    propertyCells.set(property.identifier, {value, received, property});
  }
  $('event-id').textContent = catalog.events.map(event => event.identifier).join(', ');
}
function updateControls() {
  $('connect').disabled = busy || state.connected; $('disconnect').disabled = busy || !state.connected;
  $('stop-all').disabled = !state.connected;
  for (const id of ['host','port','address','mode']) $(id).disabled = state.connected || busy;
  $('mode').querySelector('[value="remote"]').disabled = !state.remoteEnabled;
  document.querySelectorAll('.move').forEach(button => button.disabled = busy || !state.online || state.motionActive);
  document.querySelectorAll('.scope-stop').forEach(button => button.disabled = !state.connected);
}
function updateState() {
  if (state.connected) $('mode').value = state.mode;
  const gateway = state.gateway;
  $('gateway-status').textContent = !state.remoteEnabled ? '独立网关尚未启用；启动时配置 Redis 端口可启用。' :
    (gateway?.ready ? '独立网关已就绪' : '等待独立网关启动 / 心跳超时') + ' · ' + gateway.protocolCodecId + ' · ' + (gateway.serverId || '尚未注册');
  $('connection').textContent = state.online ? '设备在线' : state.connected ? '等待上线 / 离线' : '未连接';
  $('connection').className = 'pill' + (state.online ? ' online' : state.connected ? ' offline' : '');
  $('motion').textContent = state.motionActive ? '运动进行中或停止未确认，请查看记录' : state.online ? '可以下发指令' : '等待连接上线';
  if (state.target) {
    $('host').value = state.target.host; $('port').value = state.target.port; $('address').value = state.target.ptzAddress;
    $('target-kind').textContent = (['127.0.0.1','localhost'].includes(state.target.host) ? '回环目标' : '实际网络目标') + ' · ' + state.target.host + ':' + state.target.port;
  } else $('target-kind').textContent = '填写目标后连接';
  $('property-time').textContent = state.lastPropertyTime ? '最近状态 ' + time(state.lastPropertyTime) : '尚未收到状态';
  for (const [identifier, cell] of propertyCells) {
    const value = state.properties?.[identifier], present = value !== undefined && value !== null;
    let display = '未上报';
    if (present) {
      const spec = cell.property.dataSpecsList?.find(spec => String(spec.value) === String(value));
      display = spec ? spec.name + ' (' + value + ')' : identifier === 'timestamp' ? time(value) : typeof value === 'number' ? String(Math.round(value * 1000) / 1000) : String(value);
    }
    cell.value.textContent = display; cell.value.className = 'property-value' + (present ? '' : ' absent');
    cell.received.textContent = '最近接收 ' + time(state.propertyTimes?.[identifier]);
  }
  const ack = state.lastAck;
  if (ack) {
    const feedback = ack.params.event, code = feedback.statusCode;
    $('ack').textContent = time(state.lastAckTime) + ' · 状态码 ' + code + ' · ' + ({0:'正常',1:'数据不完整',2:'内容错误'}[code] || '未知状态码');
  } else $('ack').textContent = '尚未收到协议反馈';
  updateControls();
}
function recordSummary(record) {
  const data = record.data;
  if (typeof data === 'string') return data;
  if (record.type === 'RESULT') return (data.data?.deliveryState || (data.code === 0 ? '配置已接受' : '失败')) + ' · ' + (data.data?.identifier || data.method) + ' · ' + data.requestId;
  if (record.type === 'DOWNSTREAM') return (data.params?.identifier || data.method) + ' · ' + data.requestId;
  if (record.type === 'ACK') return '协议状态码 ' + data.params.event.statusCode;
  if (record.type === 'PROPERTY') return Object.keys(data.params).join(', ');
  if (record.type === 'CONNECTION') return '设备连接状态 ' + data.params.state;
  if (record.type.endsWith('_FRAME')) return data.hex.slice(0, 80) + (data.hex.length > 80 ? '…' : '');
  return data.method || record.type;
}
function visible(record) {
  const filter = $('filter').value;
  if (filter === 'all') return true;
  if (filter === 'commands') return ['DOWNSTREAM','RESULT','CONTROL','CONNECTION','LAB'].includes(record.type);
  if (filter === 'frames') return record.type.endsWith('_FRAME');
  return record.type === filter;
}
function renderRecords() {
  const open = new Set([...$('records').querySelectorAll('details[open]')].map(node => node.dataset.sequence));
  $('records').replaceChildren();
  const records = history.filter(visible).slice(-150).reverse();
  if (!records.length) $('records').append(element('p', '暂无记录', 'muted'));
  for (const record of records) {
    const details = element('details', undefined, 'record'); details.dataset.sequence = String(record.sequence); details.open = open.has(details.dataset.sequence);
    const summary = element('summary'); summary.append(element('time', time(record.time)), element('span', record.type, 'type'), element('span', recordSummary(record), 'description'));
    details.append(summary, element('pre', JSON.stringify(record.context ? {context:record.context, data:record.data} : record.data, null, 2))); $('records').append(details);
  }
}
function poll() {
  if (!pollFlight) pollFlight = pollOnce().finally(() => { pollFlight = null; });
  return pollFlight;
}
async function pollOnce() {
  state = await request('/api/state?cursor=' + cursor);
  if (state.truncated) notice('部分旧记录已超出内存窗口，已同步当前保留记录。');
  if (state.events.length) { history = [...history, ...state.events].filter((record, i, all) => i === 0 || record.sequence !== all[i - 1].sequence).slice(-1000); renderRecords(); }
  cursor = state.cursor; updateState();
}
$('connect-form').addEventListener('submit', event => { event.preventDefault(); history = []; cursor = 0; action('/api/connect', {mode:$('mode').value, host:$('host').value.trim(), port:Number($('port').value), ptzAddress:Number($('address').value)}, '配置同步已接受，等待心跳确认设备上线。'); });
$('disconnect').addEventListener('click', () => action('/api/disconnect', {}, '已停止并断开目标连接。'));
$('stop-all').addEventListener('click', () => action('/api/stop', {}, '三个执行器的停止指令已写入 TCP。', true));
$('filter').addEventListener('change', renderRecords);
(async () => {
  try { catalog = await request('/api/catalog'); renderCatalog(); await poll(); notice('物模型已加载。连接目标后可进行控制；当前页面不会自动发送运动指令。'); }
  catch (error) { notice(error.message, true); return; }
  async function refresh() {
    try { await poll(); } catch (error) { state.online = false; updateControls(); notice('Web 后台暂不可达：' + error.message, true); }
    setTimeout(refresh, 500);
  }
  setTimeout(refresh, 500);
})();
