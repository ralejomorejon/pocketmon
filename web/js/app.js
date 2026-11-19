// PocketMon web app: connects back to the agent that served this page and shows the live dashboard.
import * as F from './format.js';
import { DESIGN, classic, modern } from './dashboards.js';

const $ = (sel) => document.querySelector(sel);
const STORE = 'pcstats';
const DEFAULTS = { style: 'modern', fahrenheit: false, intervalMs: 1000, shiftIntervalSec: 60, shiftMaxPx: 8 };

// ---------------------------------------------------------------- persistent state

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORE)) ?? {};
  } catch {
    return {};
  }
}
const saved = load();
const state = {
  token: saved.token ?? null,
  pcName: saved.pcName ?? null,
  settings: { ...DEFAULTS, ...(saved.settings ?? {}) },
  welcome: null,
  snapshot: null,
  stats: emptyStats(),
  conn: 'connecting', // connecting | connected | error | pairing
  message: null,
  screen: 'dash', // dash | pair | settings
};
function persist() {
  localStorage.setItem(STORE, JSON.stringify({ token: state.token, pcName: state.pcName, settings: state.settings }));
}
function emptyStats() {
  return { up: { min: null, max: null }, down: { min: null, max: null }, cpuSeries: [], gpuSeries: [], ramSeries: [], upSeries: [], downSeries: [], fpsSeries: [] };
}
function addStats(st, s) {
  const r = s.ram;
  const ramPct = r && typeof r.usedMb === 'number' && r.totalMb > 0 ? (r.usedMb / r.totalMb) * 100 : null;
  return {
    up: F.minMax(st.up, s.net?.upBps),
    down: F.minMax(st.down, s.net?.downBps),
    cpuSeries: F.pushSeries(st.cpuSeries, s.cpu?.usage),
    gpuSeries: F.pushSeries(st.gpuSeries, s.gpu?.usage),
    ramSeries: F.pushSeries(st.ramSeries, ramPct),
    upSeries: F.pushSeries(st.upSeries, s.net?.upBps),
    downSeries: F.pushSeries(st.downSeries, s.net?.downBps),
    fpsSeries: F.pushSeries(st.fpsSeries, s.fps?.value),
  };
}

// ---------------------------------------------------------------- connection

let ws = null;
let backoff = 1000;
let retryTimer = null;
let pendingCode = null;
const clientName = (() => {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return 'iPhone (web)';
  if (/iPad/.test(ua)) return 'iPad (web)';
  if (/Android/.test(ua)) return 'Android (web)';
  return 'Browser';
})();

function wsUrl() {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
}

function connect() {
  clearTimeout(retryTimer);
  if (ws) {
    ws.onclose = null;
    ws.close();
  }
  if (!state.token && !pendingCode) {
    showPair();
    return;
  }
  state.conn = 'connecting';
  render();
  const sock = new WebSocket(wsUrl());
  ws = sock;
  sock.onopen = () => {
    const msg = pendingCode
      ? { type: 'pair', v: 1, code: pendingCode, clientName }
      : { type: 'hello', v: 1, token: state.token, clientName };
    sock.send(JSON.stringify(msg));
  };
  sock.onmessage = (ev) => {
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (msg.type === 'welcome') onWelcome(sock, msg);
    else if (msg.type === 'snapshot') onSnapshot(msg);
    else if (msg.type === 'error') onError(msg);
  };
  sock.onclose = () => {
    if (ws !== sock) return;
    ws = null;
    if (state.screen === 'pair' && !state.token) return;
    state.conn = 'error';
    state.message = `Connection lost · retry in ${Math.round(backoff / 1000)}s`;
    render();
    retryTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 10000);
  };
}

function onWelcome(sock, msg) {
  backoff = 1000;
  if (msg.token) {
    state.token = msg.token;
    state.stats = emptyStats();
  }
  pendingCode = null;
  state.pcName = msg.pcName ?? state.pcName;
  state.welcome = msg;
  state.conn = 'connected';
  state.message = null;
  persist();
  sock.send(JSON.stringify({ type: 'setInterval', intervalMs: state.settings.intervalMs }));
  if (state.screen === 'pair') state.screen = 'dash';
  render();
}

function onSnapshot(s) {
  state.snapshot = s;
  state.stats = addStats(state.stats, s);
  if (state.screen === 'dash') renderDash();
}

function onError(msg) {
  if (msg.code === 'unauthorized') {
    state.token = null;
    persist();
    showPair('This device is no longer paired. Enter the code shown in the PC tray.');
  } else if (msg.code === 'bad_code') {
    pendingCode = null;
    showPair('Wrong pairing code. Check the code in the PC tray.');
  } else {
    state.message = msg.message ?? 'Error';
  }
  if (ws) {
    ws.onclose = null;
    ws.close();
    ws = null;
  }
}

// ---------------------------------------------------------------- screens

function showPair(message = null) {
  state.screen = 'pair';
  state.conn = 'pairing';
  state.message = message;
  render();
}

function render() {
  $('#pair').hidden = state.screen !== 'pair';
  $('#settings').hidden = state.screen !== 'settings';
  $('#stage').hidden = state.screen !== 'dash';
  if (state.screen === 'pair') renderPair();
  if (state.screen === 'settings') renderSettings();
  if (state.screen === 'dash') renderDash();
}

function renderPair() {
  $('#pair-msg').textContent = state.message ?? '';
  $('#pair-host').textContent = location.host;
  const code = new URLSearchParams(location.hash.slice(1)).get('code');
  const input = $('#pair-code');
  if (code && !input.value) input.value = code.replace(/\D/g, '').slice(0, 6);
  $('#pair-btn').disabled = input.value.length !== 6;
}

function connInfo() {
  const usb = ['localhost', '127.0.0.1'].includes(location.hostname);
  if (state.conn === 'connected') return { badge: usb ? 'USB' : 'Wi-Fi', pcName: state.welcome?.pcName ?? state.pcName, note: null };
  if (state.conn === 'connecting') return { badge: null, pcName: state.pcName, note: 'Connecting…' };
  return { badge: null, pcName: state.pcName, note: state.message ?? 'Disconnected' };
}

function model() {
  const f = state.settings.fahrenheit;
  return {
    s: state.snapshot,
    welcome: state.welcome,
    stats: state.stats,
    settings: state.settings,
    conn: connInfo(),
    deg: f ? '°F' : '°C',
    t: (c) => F.tempNumber(c, f),
  };
}

function renderDash() {
  const stage = $('#stage');
  const inner = $('#inner');
  const pad = state.settings.shiftMaxPx;
  const w = stage.clientWidth - pad * 2;
  const h = stage.clientHeight - pad * 2;
  if (w <= 0 || h <= 0) return;
  const landscape = w > h;
  const [dw, dh] = DESIGN[state.settings.style][landscape ? 'landscape' : 'portrait'];
  // Lay out at (at least) the reference size, then scale down/up uniformly to fill the screen.
  const scale = Math.min(w / dw, h / dh);
  inner.style.width = `${w / scale}px`;
  inner.style.height = `${h / scale}px`;
  inner.style.transform = `scale(${scale})`;
  $('#shift').style.inset = `${pad}px`;

  inner.innerHTML = state.settings.style === 'classic' ? classic(model(), landscape) : modern(model(), landscape);
  tickClock();
  fitAll(inner);
}

/** Shrinks each .fit element's font until its content fits its width (single line, no clipping).
 *  Heights are fixed by the reference layout, and tall glyphs always overflow their line box slightly. */
function fitAll(root) {
  for (const el of root.querySelectorAll('.fit')) {
    let size = parseFloat(el.dataset.max);
    el.style.fontSize = `${size}px`;
    while (size > 6 && el.scrollWidth > el.clientWidth + 0.5) {
      size -= 0.5;
      el.style.fontSize = `${size}px`;
    }
  }
}

function tickClock() {
  const now = new Date();
  const day = $('#clk-day');
  if (!day) return;
  const lang = navigator.language;
  day.textContent = now.toLocaleDateString(lang, { weekday: 'long' }).toUpperCase();
  $('#clk-time').textContent = now.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' }).replace(/\s?[AP]M$/i, '');
  $('#clk-sec').textContent = String(now.getSeconds()).padStart(2, '0');
  $('#clk-date').textContent = now.toLocaleDateString(lang, { day: 'numeric', month: 'long', year: 'numeric' });
}

function renderSettings() {
  const s = state.settings;
  for (const [id, key] of [['set-style', 'style'], ['set-interval', 'intervalMs'], ['set-shift', 'shiftIntervalSec'], ['set-range', 'shiftMaxPx']]) {
    $(`#${id}`).value = String(s[key]);
  }
  $('#set-unit').value = s.fahrenheit ? 'f' : 'c';
  $('#set-pc').textContent = state.pcName ? `${state.pcName} · ${location.host}` : location.host;
}

// ---------------------------------------------------------------- burn-in: pixel shift

let shiftTimer = null;
const shiftPos = { x: 0, y: 0 };
function scheduleShift() {
  clearInterval(shiftTimer);
  shiftTimer = setInterval(stepShift, state.settings.shiftIntervalSec * 1000);
}
function stepShift() {
  const max = state.settings.shiftMaxPx;
  const step = Math.max(1, max / 2);
  const walk = (v) => Math.max(-max, Math.min(max, v + (Math.random() * 2 - 1) * step));
  shiftPos.x = walk(shiftPos.x);
  shiftPos.y = walk(shiftPos.y);
  $('#shift').style.transform = `translate(${shiftPos.x.toFixed(1)}px, ${shiftPos.y.toFixed(1)}px)`;
}

// ---------------------------------------------------------------- keep the screen awake

// Native Wake Lock needs a secure context (https or localhost); NoSleep falls back to a silent looping video.
const noSleep = typeof window.NoSleep === 'function' ? new window.NoSleep() : null;
let awake = false;
async function keepAwake() {
  if (awake || !noSleep) return;
  try {
    await noSleep.enable();
    awake = true;
    $('#wake-hint').hidden = true;
  } catch {
    // Needs a user gesture on some browsers; the next tap retries.
  }
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    awake = false;
    keepAwake();
    if (!ws && (state.token || pendingCode)) connect();
  }
});

// ---------------------------------------------------------------- events

function wire() {
  $('#pair-code').addEventListener('input', (e) => {
    e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6);
    $('#pair-btn').disabled = e.target.value.length !== 6;
  });
  $('#pair-form').addEventListener('submit', (e) => {
    e.preventDefault();
    keepAwake();
    pendingCode = $('#pair-code').value;
    $('#pair-msg').textContent = 'Pairing…';
    connect();
  });

  let overlayTimer = null;
  $('#stage').addEventListener('click', () => {
    keepAwake();
    const ov = $('#overlay');
    ov.hidden = !ov.hidden;
    clearTimeout(overlayTimer);
    if (!ov.hidden) overlayTimer = setTimeout(() => (ov.hidden = true), 5000);
  });
  $('#btn-settings').addEventListener('click', (e) => {
    e.stopPropagation();
    $('#overlay').hidden = true;
    state.screen = 'settings';
    render();
  });
  $('#btn-fullscreen').addEventListener('click', (e) => {
    e.stopPropagation();
    $('#overlay').hidden = true;
    if (!document.fullscreenElement) document.documentElement.requestFullscreen?.().catch(() => {});
    else document.exitFullscreen?.();
  });
  if (!document.documentElement.requestFullscreen) $('#btn-fullscreen').hidden = true;

  const onSetting = (id, key, parse) => $(`#${id}`).addEventListener('change', (e) => {
    state.settings[key] = parse(e.target.value);
    persist();
    if (key === 'intervalMs' && ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'setInterval', intervalMs: state.settings.intervalMs }));
    if (key === 'shiftIntervalSec') scheduleShift();
  });
  onSetting('set-style', 'style', String);
  onSetting('set-interval', 'intervalMs', Number);
  onSetting('set-shift', 'shiftIntervalSec', Number);
  onSetting('set-range', 'shiftMaxPx', Number);
  $('#set-unit').addEventListener('change', (e) => {
    state.settings.fahrenheit = e.target.value === 'f';
    persist();
  });
  $('#set-done').addEventListener('click', () => {
    state.screen = state.token ? 'dash' : 'pair';
    render();
  });
  $('#set-forget').addEventListener('click', () => {
    if (!confirm('Forget this PC? You will need a new pairing code.')) return;
    state.token = null;
    state.pcName = null;
    state.snapshot = null;
    persist();
    if (ws) {
      ws.onclose = null;
      ws.close();
      ws = null;
    }
    showPair();
  });
  $('#pair-settings').addEventListener('click', () => {
    state.screen = 'settings';
    render();
  });

  window.addEventListener('resize', () => state.screen === 'dash' && renderDash());
  setInterval(tickClock, 1000);
}

wire();
scheduleShift();
if (!noSleep) $('#wake-hint').hidden = true;
if ('serviceWorker' in navigator && window.isSecureContext) navigator.serviceWorker.register('sw.js').catch(() => {});
if (state.token) connect();
else showPair();
