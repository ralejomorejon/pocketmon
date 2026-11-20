// Dashboard markup for the Modern and Classic designs. Rendered at a reference size and scaled to fit by app.js.
import * as F from './format.js';
import { C, arcGauge, centerText, coreBars, heatColor, ringGauge, sparkline, tempGauge, usageBar } from './gauges.js';

const e = F.escapeHtml;
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

export const DESIGN = {
  modern: { landscape: [860, 380], portrait: [400, 860] },
  classic: { landscape: [820, 460], portrait: [380, 780] },
};

const CPU_MIN = 20;
const CPU_MAX = 100;
const GPU_MIN = 20;
const GPU_MAX = 105;

/** "118" + small gray " W" (no unit after a dash). */
const vu = (value, unit) => (value === F.DASH || !unit ? e(value) : `${e(value)}<small> ${e(unit)}</small>`);

/** Text that app.js shrinks until it fits its box (never clips or wraps). */
const fit = (html, max, cls = '', color = null) =>
  `<div class="fit ${cls}" data-max="${max}" style="font-size:${max}px${color ? `;color:${color}` : ''}">${html}</div>`;

// ---------------------------------------------------------------- modern

function header(title, right) {
  return `<div class="ph"><i class="dot"></i><span class="pt">${e(title)}</span><span class="pr">${e(right ?? '')}</span></div>`;
}

function stat(label, valueHtml, color) {
  return `<div class="stat"><div class="lbl">${e(label)}</div>${fit(valueHtml, 21, 'sv', color)}</div>`;
}

function legend(items) {
  return `<div class="legend">${items.map(([color, label, dashed]) =>
    `<span><i class="${dashed ? 'dash' : 'line'}" style="--c:${color}"></i>${e(label)}</span>`).join('')}</div>`;
}

function clockPanel(m) {
  const note = m.conn.note;
  const text = note ?? (m.conn.pcName ?? '');
  return `<div class="panel clock">
    <div class="day" id="clk-day"></div>
    <div class="time-box">${fit('<span id="clk-time"></span><sup id="clk-sec"></sup>', 76, 'time')}</div>
    ${fit('<span id="clk-date"></span>', 17, 'date')}
    <div class="conn">${m.conn.badge ? `<span class="badge">${e(m.conn.badge)}</span>` : ''}<span class="${note ? 'warn' : ''}">${e(text)}</span></div>
  </div>`;
}

function netRing(bps, peak, color, label, total) {
  const frac = isNum(bps) ? bps / Math.max(peak ?? bps, 125000) : null;
  return `<div class="netring">
    <div class="gbox">${ringGauge(frac, color, { stroke: 6, ticks: true, inner: centerText(F.mbps(bps), { size: 19, y: 46 }) + centerText('Mbps', { size: 8, fill: C.label, weight: 400, y: 62 }) })}</div>
    ${fit(`Peak ${e(F.mbps(peak))} Mbps`, 9, 'muted c')}
    <div class="lg c"><i style="background:${color}"></i>${e(label)}</div>
    ${fit(e(total), 9, 'muted c')}
  </div>`;
}

function networkPanel(m) {
  const n = m.s?.net;
  return `<div class="panel">${header('Network', n?.adapter)}
    <div class="row grow gap10">
      ${netRing(n?.downBps, m.stats.down.max, C.cool, 'Download', `${F.bytes(n?.receivedBytes)} received`)}
      ${netRing(n?.upBps, m.stats.up.max, C.upload, 'Upload', `${F.bytes(n?.sentBytes)} sent`)}
    </div></div>`;
}

function cpuPanel(m, wide) {
  const c = m.s?.cpu;
  const limit = c?.tempLimit;
  const maxC = isNum(limit) && limit + 5 > CPU_MAX ? Math.ceil((limit + 5) / 5) * 5 : CPU_MAX;
  const gauge = tempGauge({
    tempC: c?.temp, load: c?.usage, tempText: m.t(c?.temp), unit: m.deg,
    subtitle: isNum(limit) && isNum(c?.temp) ? `${Math.max(0, Math.round(limit - c.temp))}° below limit` : null,
    minLabel: `${m.t(CPU_MIN)}°`, maxLabel: `${m.t(maxC)}°`, minC: CPU_MIN, maxC,
    limitC: limit, limitLabel: isNum(limit) ? `${m.t(limit)}°` : null,
  });
  const loads = c?.coreLoads ?? [];
  return `<div class="panel">${header('CPU', m.welcome?.cpuName)}
    <div class="row grow">
      <div class="gcol" style="flex:${wide ? 1.2 : 1.6}"><div class="gbox">${gauge}</div>${legend([[heatColor(c?.temp), 'Temp'], [C.loadArc, 'Load']])}</div>
      <div class="scol">
        ${stat('Load', vu(F.percentNumber(c?.usage), '%'))}
        ${stat('Power', vu(F.watts(c?.powerW), 'W'))}
        ${stat('Clock', vu(F.ghz(c?.clockMhz), 'GHz'))}
      </div>
    </div>
    <div class="row small"><span class="muted grow">Per-core load</span>${loads.length ? `<span>${loads.length} cores</span>` : ''}</div>
    ${coreBars(loads)}
  </div>`;
}

function gpuPanel(m, wide) {
  const g = m.s?.gpu;
  const hs = g?.hotspotTemp;
  const gauge = tempGauge({
    tempC: g?.temp, load: g?.usage, tempText: m.t(g?.temp), unit: m.deg,
    subtitle: isNum(hs) && isNum(g?.temp) ? `Hotspot +${Math.max(0, Math.round(hs - g.temp))}°` : null,
    minLabel: `${m.t(GPU_MIN)}°`, maxLabel: `${m.t(GPU_MAX)}°`, minC: GPU_MIN, maxC: GPU_MAX, hotspotC: hs,
  });
  const leg = [[heatColor(g?.temp), 'Temp']];
  if (isNum(hs)) leg.push([heatColor(hs), 'Hotspot', true]);
  leg.push([C.loadArc, 'Load']);
  return `<div class="panel">${header('GPU', m.welcome?.gpuName)}
    <div class="row grow">
      <div class="gcol" style="flex:${wide ? 1.2 : 1.6}"><div class="gbox">${gauge}</div>${legend(leg)}</div>
      <div class="scol">
        ${isNum(hs) ? stat('Hotspot', e(`${m.t(hs)}°`), heatColor(hs)) : ''}
        ${stat('Load', vu(F.percentNumber(g?.usage), '%'))}
        ${stat('Power', vu(F.watts(g?.powerW), 'W'))}
        ${stat('Clock', vu(F.ghz(g?.clockMhz), 'GHz'))}
      </div>
    </div>
    <div class="row small"><span class="muted grow">VRAM</span><span>${e(F.memPair(g?.vramUsedMb, g?.vramTotalMb))}</span></div>
    ${usageBar(F.fraction(g?.vramUsedMb, g?.vramTotalMb))}
  </div>`;
}

function memoryPanel(m) {
  const r = m.s?.ram;
  const frac = F.fraction(r?.usedMb, r?.totalMb);
  const free = isNum(r?.usedMb) && isNum(r?.totalMb) ? r.totalMb - r.usedMb : null;
  const pct = frac === null ? F.DASH : String(Math.round(frac * 100));
  return `<div class="panel">${header('Memory')}
    <div class="row grow">
      <div class="gbox" style="flex:1">${ringGauge(frac, C.amber, { inner: centerText(pct, { size: 26, fill: C.amber, small: '%', smallFill: C.amber }) })}</div>
      <div class="memtext">
        <div class="lbl">In use</div>${fit(vu(F.memGb(r?.usedMb), `/ ${F.memGb(r?.totalMb)} GB`), 16)}
        <div class="lbl">Free</div>${fit(vu(F.memGb(free), 'GB'), 16)}
      </div>
    </div></div>`;
}

/** Game logo (or name) with the FPS counter in the display font; side by side in the short portrait panel. */
function nowPlaying(fps, row = false) {
  const title = fps.game ?? fps.app?.replace(/\.exe$/i, '') ?? '';
  const logo = fps.logo
    ? `<img class="np-logo" src="${e(fps.logo)}" alt="${e(title)}">`
    : `<div class="np-name">${fit(e(title), 20)}</div>`;
  return `<div class="np${row ? ' row' : ''}">${logo}${fpsCounter(fps)}</div>`;
}

/** FPS number + label. Per-game style (e.g. Zelda: Cinzel in the logo's gold); the agent sends a font id and #RRGGBB. */
function fpsCounter(fps, cls = '') {
  const font = /^[a-z0-9-]+$/.test(fps.font ?? '') ? ` font-${fps.font}` : '';
  const color = /^#[0-9a-f]{6}$/i.test(fps.color ?? '') ? ` style="--np-color:${fps.color}"` : '';
  return `<div class="np-fps${font} ${cls}"${color}><span class="np-num">${e(F.fps(fps.value))}</span><span class="np-unit">FPS</span></div>`;
}

function storagePanel(m) {
  const disks = m.s?.disks ?? [];
  const fps = m.s?.fps;
  // While a game is running this panel belongs to it: the logo gets the whole space; drives return afterwards.
  if (fps) return `<div class="panel">${header('Now playing')}${nowPlaying(fps)}</div>`;
  const rows = disks.slice(0, 4).map((d) => {
    const used = isNum(d.freeMb) && isNum(d.totalMb) && d.totalMb > 0 ? 1 - d.freeMb / d.totalMb : null;
    const color = used === null ? C.label : used >= 0.9 ? C.heat : used >= 0.75 ? C.amber : C.cool;
    return `<div class="disk">
      <div class="dring">${ringGauge(used, color, { stroke: 9, inner: centerText(used === null ? F.DASH : String(Math.round(used * 100)), { size: 30, weight: 400 }) })}</div>
      <div class="dtext"><div class="ell">${e([d.name, d.model].filter(Boolean).join(' '))}</div>
        <div class="row">${fit(`${e(F.size(d.freeMb))} free of ${e(F.size(d.totalMb))}`, 9.5, 'muted grow')}${isNum(d.temp) ? `<span class="muted t">${e(m.t(d.temp))}°</span>` : ''}</div></div>
    </div>`;
  });
  return `<div class="panel">${header('Storage')}<div class="disks">${rows.join('') || `<div class="muted">${F.DASH}</div>`}</div></div>`;
}

export function modern(m, landscape) {
  if (landscape) {
    return `<div class="modern row fill">
      <div class="col" style="flex:.95"><div class="cell" style="flex:1">${clockPanel(m)}</div><div class="cell bt" style="flex:1.1">${networkPanel(m)}</div></div>
      <div class="cell bl" style="flex:1.45">${cpuPanel(m, false)}</div>
      <div class="cell bl" style="flex:1.45">${gpuPanel(m, false)}</div>
      <div class="col bl" style="flex:1"><div class="cell" style="flex:${0.8}">${memoryPanel(m)}</div><div class="cell bt" style="flex:${1.2}">${storagePanel(m)}</div></div>
    </div>`;
  }
  return `<div class="modern col fill">
    <div class="row" style="flex:.85"><div class="cell" style="flex:1">${clockPanel(m)}</div><div class="cell bl" style="flex:1">${memoryPanel(m)}</div></div>
    <div class="cell bt" style="flex:1.3">${cpuPanel(m, true)}</div>
    <div class="cell bt" style="flex:1.3">${gpuPanel(m, true)}</div>
    <div class="row bt" style="flex:1"><div class="cell" style="flex:1">${networkPanel(m)}</div><div class="cell bl" style="flex:1">${storagePanel(m)}</div></div>
  </div>`;
}

// ---------------------------------------------------------------- classic

const metric = (label, value, color) =>
  `<div class="metric"><span>${e(label)}</span><b style="${value === F.DASH ? `color:${C.label}` : color ? `color:${color}` : ''}">${e(value)}</b></div>`;

const tempColor = (c) => ({ hot: C.hot, warm: C.warm, normal: C.value })[F.tempLevel(c)] ?? C.label;

function tileHeader(title, sub) {
  return `<div class="th"><b>${e(title)}</b>${sub ? `<span>${e(sub)}</span>` : ''}</div>`;
}

function gaugeRow(frac, gaugeText, label, value, color) {
  return `<div class="grow-row"><div class="arc">${arcGauge(frac, gaugeText)}</div>
    <div class="gr"><span>${e(label)}</span><b style="color:${value === F.DASH ? C.label : color ?? C.value}">${e(value)}</b></div></div>`;
}

function classicTiles(m) {
  const s = m.s;
  const f = m.settings.fahrenheit;
  const c = s?.cpu;
  const g = s?.gpu;
  const r = s?.ram;
  const n = s?.net;
  const ramFrac = F.fraction(r?.usedMb, r?.totalMb);
  const tiles = [
    `${tileHeader('CPU', m.welcome?.cpuName)}${gaugeRow(isNum(c?.usage) ? c.usage / 100 : null, F.percent(c?.usage), 'Temp', F.temp(c?.temp, f), tempColor(c?.temp))}
      ${metric('Clock', F.clock(c?.clockMhz))}${metric('Power', F.power(c?.powerW))}<div class="sp">${sparkline(m.stats.cpuSeries, C.accent + 'B3', 100)}</div>`,
    `${tileHeader('GPU', m.welcome?.gpuName)}${gaugeRow(isNum(g?.usage) ? g.usage / 100 : null, F.percent(g?.usage), 'Temp', F.temp(g?.temp, f), tempColor(g?.temp))}
      ${metric('Clock', F.clock(g?.clockMhz))}${metric('Power', F.power(g?.powerW))}${metric('VRAM', F.memPair(g?.vramUsedMb, g?.vramTotalMb))}
      <div class="sp">${sparkline(m.stats.gpuSeries, C.accent + 'B3', 100)}</div>`,
    `${tileHeader('RAM')}${gaugeRow(ramFrac, ramFrac === null ? F.DASH : F.percent(ramFrac * 100), 'Used', F.mem(r?.usedMb))}
      ${metric('Total', F.mem(r?.totalMb))}<div class="sp">${sparkline(m.stats.ramSeries, C.accent + 'B3', 100)}</div>`,
    `${tileHeader('NETWORK', n?.adapter)}${metric('↑ Up', F.rate(n?.upBps))}<div class="sp">${sparkline(m.stats.upSeries, C.warm + '99')}</div>
      ${metric('↓ Down', F.rate(n?.downBps))}<div class="sp">${sparkline(m.stats.downSeries, C.accent + 'B3')}</div>`,
    `${tileHeader('FANS')}${(s?.fans ?? []).length ? s.fans.map((x) => metric(x.name ?? 'Fan', F.rpm(x.rpm))).join('') : `<div class="muted">${F.DASH}</div>`}`,
  ];
  if (s?.fps) {
    tiles.push(`${tileHeader('FPS', s.fps.logo ? null : (s.fps.game ?? s.fps.app))}${s.fps.logo ? `<img class="fps-logo" src="${e(s.fps.logo)}" alt="${e(s.fps.game ?? '')}">` : ''}${fpsCounter(s.fps)}<div class="sp">${sparkline(m.stats.fpsSeries, C.accent + 'B3')}</div>`);
  }
  return tiles;
}

export function classic(m, landscape) {
  const cols = landscape ? 3 : 2;
  const tiles = classicTiles(m);
  const rows = [];
  for (let i = 0; i < tiles.length; i += cols) {
    const row = tiles.slice(i, i + cols).map((t) => `<div class="tile">${t}</div>`);
    while (row.length < cols) row.push('<div class="tile-spacer"></div>');
    rows.push(`<div class="trow">${row.join('')}</div>`);
  }
  const conn = m.conn;
  return `<div class="classic col fill">
    <div class="status">${conn.badge ? `<span class="badge">${e(conn.badge)}</span>` : ''}<span class="muted">${e(conn.pcName ?? 'PC')}</span>
      <span class="grow"></span>${conn.note ? `<span class="warn">${e(conn.note)}</span>` : ''}</div>
    <div class="tgrid">${rows.join('')}</div>
  </div>`;
}
