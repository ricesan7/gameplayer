const $ = (s: string) => document.querySelector(s) as HTMLElement | null;

const logEl = $('#log') as HTMLPreElement;
const stateEl = $('#state')!;
const iframe = $('#box') as HTMLIFrameElement;
const fileIn = $('#file') as HTMLInputElement;
const fileNameEl = $('#filename')!;
const urlIn = $('#url') as HTMLInputElement;

function log(msg: string, level: 'info' | 'error' | 'warn' = 'info') {
  const line = `[${new Date().toLocaleTimeString()}] ${level.toUpperCase()}: ${msg}\n`;
  if (logEl) {
    logEl.textContent += line;
    logEl.scrollTop = logEl.scrollHeight;
  }
  (level === 'error' ? console.error : console.log)(msg);
}
function setState(s: string) { stateEl.textContent = s; }

// iframe（sandbox）は public/sandbox/index.html を同一オリジンで読み込む
function bootSandbox() {
  const src = new URL('sandbox/', import.meta.env.BASE_URL).toString();
  iframe.src = src;
  boxReady = false;
  setState('booting');
  log('sandbox boot → ' + src);
}

let boxReady = false;
window.addEventListener('message', (ev) => {
  const d = (ev.data ?? {}) as any;
  if (d.type === 'ready') { boxReady = true; log('sandbox ready'); }
  else if (d.type === 'log') { log(d.msg, d.level ?? 'info'); }
});

async function runCodeWhenReady(code: string) {
  for (let i = 0; i < 40 && !boxReady; i++) await new Promise(r => setTimeout(r, 50));
  if (!boxReady) { log('iframe not ready', 'error'); return; }
  iframe.contentWindow?.postMessage({ type: 'runCode', code }, '*');
  setState('running');
}

// 実行: ファイル
async function runLocal() {
  const f = fileIn.files?.[0];
  if (!f) { log('ファイルが選択されていません', 'warn'); return; }
  if (!f.name.endsWith('.js')) { log('.js ファイルを選んでください', 'warn'); return; }
  bootSandbox();
  const text = await f.text();
  const needsExport = !/export\s+default\s+/m.test(text);
  const wrapped = needsExport ? `${text}\nexport default game;` : text;
  await runCodeWhenReady(wrapped);
  log(`実行開始(ローカル): ${f.name}`);
}

// 実行: URL
async function runFromURL() {
  const url = (urlIn.value || '').trim();
  if (!url) { log('URLを入力してください', 'warn'); return; }
  if (location.protocol === 'https:' && url.startsWith('http:')) {
    log('Mixed Content: httpsページでhttpは不可です', 'error'); return;
  }
  bootSandbox();
  try {
    log('fetch: ' + url);
    const res = await fetch(url, { cache: 'no-store', mode: 'cors' });
    if (!res.ok) { log(`HTTPエラー: ${res.status} ${res.statusText}`, 'error'); return; }
    const text = await res.text();
    const needsExport = !/export\s+default\s+/m.test(text);
    const wrapped = needsExport ? `${text}\nexport default game;` : text;
    await runCodeWhenReady(wrapped);
    log('実行開始(URL): ' + url);
  } catch (e: any) {
    log('URL読み込み失敗: ' + (e?.message || e), 'error');
  }
}

// 実行: サンプル（public/samples/sample-game.js）
async function runSample() {
  bootSandbox();
  try {
    const url = new URL('samples/sample-game.js', import.meta.env.BASE_URL).toString();
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const text = await res.text();
    await runCodeWhenReady(text);
    log('実行開始: sample-game.js');
  } catch (e: any) {
    log('sample fetch failed: ' + (e?.message || e), 'error');
  }
}

// クリック
(document.getElementById('runLocal') as HTMLButtonElement).onclick = (e) => { e.preventDefault(); runLocal(); };
(document.getElementById('runURL') as HTMLButtonElement).onclick   = (e) => { e.preventDefault(); runFromURL(); };
(document.getElementById('runSample') as HTMLButtonElement).onclick= (e) => { e.preventDefault(); runSample(); };
(document.getElementById('restart') as HTMLButtonElement).onclick  = (e) => { e.preventDefault(); bootSandbox(); setState('idle'); log('restart'); };

// ファイル名表示
fileIn.addEventListener('change', () => {
  const f = fileIn.files?.[0];
  fileNameEl.textContent = f ? f.name : '未選択';
});

// 仮想コントローラ（常時表示 / 自動非表示なし）
const MAP = {
  up:'kUp', down:'kDown', left:'kLeft', right:'kRight',
  a:'kA', b:'kB', l:'kL', r:'kR', start:'kStart', select:'kSelect'
} as const;
const KEY = {
  up:'ArrowUp', down:'ArrowDown', left:'ArrowLeft', right:'ArrowRight',
  a:'z', b:'x', l:'q', r:'w', start:'Enter', select:'Shift'
} as const;

function bindVKey(id: string, key: string) {
  const el = document.getElementById(id);
  if (!el) return;
  const send = (down: boolean) => {
    if (!iframe.contentWindow) return;
    iframe.contentWindow.postMessage({ type:'vkey', key, down }, '*');
    el.classList.toggle('on', !!down);
  };
  const downEv = (e: Event) => { e.preventDefault(); send(true); };
  const upEv   = (e: Event) => { e.preventDefault(); send(false); };
  ['pointerdown','touchstart','mousedown'].forEach(ev=> el.addEventListener(ev, downEv, { passive:false }));
  ['pointerup','pointercancel','touchend','touchcancel','mouseup','mouseleave'].forEach(ev=> el.addEventListener(ev, upEv, { passive:false }));
}
Object.entries(MAP).forEach(([name, id]) => bindVKey(id, (KEY as any)[name]));

// 初期表示
bootSandbox();

// グローバルエラーもログへ
window.onerror = (m, src, line, col, err) => {
  log(`JS ERROR: ${m} @${src}:${line}:${col} ${err && (err as any).stack ? '\n'+(err as any).stack : ''}`, 'error');
};
window.onunhandledrejection = (e: PromiseRejectionEvent) => {
  log(`PROMISE ERROR: ${ (e.reason && e.reason.message) ? e.reason.message : e.reason }`, 'error');
};
