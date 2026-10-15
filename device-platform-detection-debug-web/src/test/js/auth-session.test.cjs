'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../main/resources/web/app.js'),'utf8');
const html=fs.readFileSync(path.join(__dirname,'../../main/resources/web/index.html'),'utf8');
const token='Bearer synthetic-page-token';
const flush=()=>new Promise(resolve=>setImmediate(resolve));

// Exercise the shipped page script with synthetic DOM/network boundaries; no real browser session or device.
function fixture(storage=new Map(),backend={id:'local-session-one',requests:[],state:{platformAuthentication:'NOT_CONNECTED',zone:'SIM-ZONE',journal:[]}},storageDenied=false){
  function element(){return {value:'',placeholder:'',disabled:false,textContent:'',className:'',children:[],parentElement:{},append(...rows){this.children.push(...rows);},replaceChildren(){this.children=[];},reportValidity(){return true;}};}
  const elements=new Map([...html.matchAll(/id="([^"]+)"/g)].map(match=>[match[1],element()]));
  const document={getElementById:id=>elements.get(id),querySelectorAll:()=>[],createElement:element};
  const sessionStorage={
    getItem(key){if(storageDenied)throw Error('storage denied');return storage.get(key)||null;},
    setItem(key,value){if(storageDenied)throw Error('storage denied');storage.set(key,value);},
    removeItem(key){if(storageDenied)throw Error('storage denied');storage.delete(key);}
  };
  const fetch=async(url,options)=>{
    backend.requests.push({url,options});let status=200,body;
    if(url==='/api/defaults')body={zone:'SIM-ZONE'};
    else if(url==='/api/login'){
      if(options.headers.Authorization!==token){status=409;body={code:'PLATFORM_AUTH_REJECTED',error:'平台拒绝本次认证（401）'};}
      else {backend.state.platformAuthentication='STORED';backend.state.zone=JSON.parse(options.body).zone;body={...backend.state,sessionId:backend.id};}
    }else if(options.headers['X-Debug-Session']!==backend.id){status=401;body={error:'Connect first'};}
    else if(url==='/api/scene-check'&&backend.reject){backend.state.platformAuthentication='REJECTED';status=409;body={code:'PLATFORM_AUTH_REJECTED',error:'平台拒绝本次认证（401）'};}
    else body={...backend.state};
    return {ok:status<400,status,json:async()=>JSON.parse(JSON.stringify(body))};
  };
  const context=vm.createContext({document,sessionStorage,fetch,setInterval(){},console});vm.runInContext(source,context);
  const get=id=>elements.get(id);
  return {storage,backend,get,async ready(){await flush();await flush();},async login(){get('authorization').value=token;await get('login').onclick();}};
}

test('login retains visible input; only the local session capability is stored for refresh',async()=>{
  const f=fixture();await f.ready();await f.login();
  assert.match(html,/id="authorization" type="text"/);
  assert.equal(f.get('authorization').value,token);
  assert.equal(f.storage.get('optical-debug-session'),f.backend.id);
  assert.equal(JSON.stringify([...f.storage]).includes(token),false);
  assert.match(f.get('connection').textContent,/Token 保存在本地服务/);
  f.get('target').value='SIM-UAV-01';f.get('longitude').value='120';f.get('latitude').value='30';f.get('altitude').value='100';
  await f.get('point-once').onclick();await f.get('point-once').onclick();
  assert.equal(f.backend.requests.filter(r=>r.url==='/api/point-once').length,2);
  assert.equal(f.get('authorization').value,token);
  assert.equal(f.backend.requests.filter(r=>r.url==='/api/login').length,1);
});

test('blank reconnect reuses the server session without sending or clearing a token',async()=>{
  const f=fixture();await f.ready();await f.login();f.get('authorization').value='';
  await f.get('login').onclick();
  assert.equal(f.backend.requests.filter(r=>r.url==='/api/login').length,1);
  assert.match(f.get('notice').textContent,/沿用服务中保存/);
  f.get('zone').value='DIFFERENT-ZONE';await f.get('login').onclick();
  assert.match(f.get('notice').textContent,/修改防区需要填写 Token/);
  assert.equal(f.backend.state.zone,'SIM-ZONE');
});

test('page refresh resumes session and defence zone without returning the platform token',async()=>{
  const first=fixture();await first.ready();await first.login();
  const before=first.backend.requests.length;
  const second=fixture(first.storage,first.backend);await second.ready();
  assert.equal(second.get('authorization').value,'');
  assert.equal(second.get('zone').value,'SIM-ZONE');
  assert.match(second.get('connection').textContent,/已连接/);
  assert.match(second.get('notice').textContent,/已恢复本地会话/);
  assert.deepEqual(first.backend.requests.slice(before).map(r=>r.url),['/api/state','/api/defaults']);
});

test('platform authentication rejection preserves local recovery access and requires renewal',async()=>{
  const f=fixture();await f.ready();await f.login();f.backend.reject=true;
  await f.get('scene-check').onclick();
  assert.match(f.get('connection').textContent,/平台拒绝认证/);
  assert.equal(f.storage.get('optical-debug-session'),f.backend.id);
  assert.equal(f.get('authorization').value,token);
  f.backend.reject=false;await f.login();
  assert.match(f.get('connection').textContent,/Token 保存在本地服务/);
});

test('service restart rejects stale local capability and asks for one new login',async()=>{
  const first=fixture();await first.ready();await first.login();
  first.backend.id='local-session-after-restart';first.backend.state.platformAuthentication='NOT_CONNECTED';
  const next=fixture(first.storage,first.backend);await next.ready();
  assert.equal(next.storage.has('optical-debug-session'),false);
  assert.match(next.get('connection').textContent,/未连接/);
  assert.match(next.get('notice').textContent,/本地调试会话已失效/);
  assert.equal(next.backend.requests.filter(r=>r.url==='/api/login').length,1);
});

test('disabled browser storage reports limited refresh recovery without breaking current login',async()=>{
  const f=fixture(new Map(),undefined,true);await f.ready();await f.login();
  assert.match(f.get('notice').textContent,/浏览器不允许保存/);
  assert.equal(f.get('authorization').value,token);
  assert.match(f.get('connection').textContent,/已连接/);
});
