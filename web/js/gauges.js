// SVG/HTML builders for the dashboard gauges. Each returns markup; gauges draw in a 100×100 viewBox and scale
// to whatever box they're placed in.
import { DASH, escapeHtml } from './format.js';

export const C = {
  black: '#000000',
  value: '#E2E6EB',
  label: '#858D97',
  labelDim: '#555B63',
  outline: '#1A1E24',
  track: '#181C22',
  tick: '#2A3038',
  accent: '#8FB3C9',
  warm: '#C9A86A',
  hot: '#C0766E',
  live: '#3FB950',
  cool: '#4FB0F5',
  amber: '#E2AA5E',
  amberDeep: '#A77A3E',
  heat: '#FF5B3A',
  heatDeep: '#D9342B',
  alert: '#FF4D4D',
  loadArc: '#6E7681',
  upload: '#9AA4AF',
  barIdle: '#232A33',
};

const START = 135;
const SWEEP = 270;
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

export function heatColor(c) {
  if (!isNum(c)) return C.label;
  if (c < 50) return C.cool;
  if (c < 70) return C.amber;
  return C.heat;
}

function hex(c) {
  const n = parseInt(c.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

export function lerpColor(a, b, t) {
  const [r1, g1, b1] = hex(a);
  const [r2, g2, b2] = hex(b);
  const k = Math.min(1, Math.max(0, t));
  const m = (x, y) => Math.round(x + (y - x) * k).toString(16).padStart(2, '0');
  return `#${m(r1, r2)}${m(g1, g2)}${m(b1, b2)}`;
}

export function loadColor(pct) {
  const f = Math.min(1, Math.max(0, (pct ?? 0) / 100));
  return f < 0.5 ? lerpColor(C.barIdle, C.amber, f / 0.5) : lerpColor(C.amber, C.heat, (f - 0.5) / 0.5);
}

const pt = (r, deg) => {
  const a = (deg * Math.PI) / 180;
  return [50 + r * Math.cos(a), 50 + r * Math.sin(a)];
};
const f2 = (n) => n.toFixed(2);

function arc(r, start, sweep) {
  const s = Math.max(0.01, sweep);
  const [x0, y0] = pt(r, start);
  const [x1, y1] = pt(r, start + s);
  return `M${f2(x0)} ${f2(y0)} A${r} ${r} 0 ${s > 180 ? 1 : 0} 1 ${f2(x1)} ${f2(y1)}`;
}

let gradId = 0;

/** Large temperature gauge: tick ring (lit to the hotspot, if any), thick temp arc with knob, inner load arc. */
export function tempGauge(o) {
  const color = heatColor(o.tempC);
  const frac = (v) => (isNum(v) ? Math.min(1, Math.max(0, (v - o.minC) / (o.maxC - o.minC))) : 0);
  const progressR = 37;
  const parts = [];

  const hsFrac = isNum(o.hotspotC) ? frac(o.hotspotC) : -1;
  const hsColor = heatColor(o.hotspotC);
  const ticks = 54;
  for (let i = 0; i <= ticks; i++) {
    const f = i / ticks;
    const a = START + SWEEP * f;
    const lit = f <= hsFrac;
    const inner = lit || i % 9 === 0 ? 42 : 43;
    const [x0, y0] = pt(inner, a);
    const [x1, y1] = pt(47.5, a);
    parts.push(`<line x1="${f2(x0)}" y1="${f2(y0)}" x2="${f2(x1)}" y2="${f2(y1)}" stroke="${lit ? hsColor : C.tick}" stroke-width="${lit ? 1.2 : 0.6}"/>`);
  }

  parts.push(`<path d="${arc(progressR, START, SWEEP)}" fill="none" stroke="${C.track}" stroke-width="5" stroke-linecap="round"/>`);
  if (isNum(o.tempC)) {
    const id = `g${gradId++}`;
    const f = frac(o.tempC);
    parts.push(`<defs><linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="15" y1="85" x2="85" y2="30">` +
      `<stop offset="0" stop-color="${lerpColor(color, C.heatDeep, 0.45)}"/><stop offset="1" stop-color="${color}"/></linearGradient></defs>`);
    parts.push(`<path d="${arc(progressR, START, SWEEP * f)}" fill="none" stroke="url(#${id})" stroke-width="5" stroke-linecap="round"/>`);
    const [kx, ky] = pt(progressR, START + SWEEP * f);
    parts.push(`<circle cx="${f2(kx)}" cy="${f2(ky)}" r="4.2" fill="${C.black}" stroke="${color}" stroke-width="1.9"/>`);
  }

  parts.push(`<path d="${arc(30, START, SWEEP)}" fill="none" stroke="${C.track}" stroke-width="1.2" stroke-linecap="round"/>`);
  if (isNum(o.load) && o.load > 0) {
    parts.push(`<path d="${arc(30, START, SWEEP * Math.min(1, o.load / 100))}" fill="none" stroke="${C.loadArc}" stroke-width="1.9" stroke-linecap="round"/>`);
  }

  const label = (text, [x, y], fill = C.label) =>
    `<text x="${f2(x)}" y="${f2(y)}" fill="${fill}" font-size="4.5" text-anchor="middle" dominant-baseline="middle">${escapeHtml(text)}</text>`;
  const [sx, sy] = pt(progressR, START);
  const [ex, ey] = pt(progressR, START + SWEEP);
  parts.push(label(o.minLabel, [sx, sy + 11]));
  parts.push(label(o.maxLabel, [ex, ey + 11]));

  if (isNum(o.limitC)) {
    const a = START + SWEEP * frac(o.limitC);
    const [x0, y0] = pt(progressR - 4.5, a);
    const [x1, y1] = pt(progressR + 8, a);
    parts.push(`<line x1="${f2(x0)}" y1="${f2(y0)}" x2="${f2(x1)}" y2="${f2(y1)}" stroke="${C.alert}" stroke-width="1.1" stroke-linecap="round"/>`);
    if (o.limitLabel) parts.push(label(o.limitLabel, pt(progressR + 15.5, a), C.alert));
  }

  const has = isNum(o.tempC);
  parts.push(`<text x="50" y="${o.subtitle ? 51 : 53}" text-anchor="middle" dominant-baseline="middle" font-size="27" font-weight="300" fill="${has ? color : C.label}">` +
    `${escapeHtml(o.tempText)}${has ? `<tspan font-size="8" fill="${C.label}" dy="-9">${escapeHtml(o.unit)}</tspan>` : ''}</text>`);
  if (o.subtitle) parts.push(`<text x="50" y="66" text-anchor="middle" font-size="4.5" fill="${C.label}">${escapeHtml(o.subtitle)}</text>`);

  return `<svg class="gauge" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet">${parts.join('')}</svg>`;
}

/** 270° ring with optional outer ticks; `inner` is SVG markup drawn in the middle. */
export function ringGauge(fractionValue, color, { stroke = 7.5, ticks = false, inner = '' } = {}) {
  const r = ticks ? 39 : 50 - stroke;
  const parts = [];
  if (ticks) {
    for (let i = 0; i <= 40; i++) {
      const a = START + (SWEEP * i) / 40;
      const [x0, y0] = pt(45, a);
      const [x1, y1] = pt(49, a);
      parts.push(`<line x1="${f2(x0)}" y1="${f2(y0)}" x2="${f2(x1)}" y2="${f2(y1)}" stroke="${C.tick}" stroke-width="0.8"/>`);
    }
  }
  parts.push(`<path d="${arc(r, START, SWEEP)}" fill="none" stroke="${C.track}" stroke-width="${stroke}" stroke-linecap="round"/>`);
  if (isNum(fractionValue) && fractionValue > 0) {
    parts.push(`<path d="${arc(r, START, SWEEP * Math.min(1, fractionValue))}" fill="none" stroke="${color}" stroke-width="${stroke}" stroke-linecap="round"/>`);
  }
  return `<svg class="gauge" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet">${parts.join('')}${inner}</svg>`;
}

export function centerText(text, { size = 22, fill = C.value, weight = 300, y = 50, small = null, smallFill = C.label } = {}) {
  const main = `<text x="50" y="${y}" text-anchor="middle" dominant-baseline="middle" font-size="${size}" font-weight="${weight}" fill="${fill}">${escapeHtml(text)}` +
    (small && text !== DASH ? `<tspan font-size="${size * 0.4}" fill="${smallFill}" dx="1">${escapeHtml(small)}</tspan>` : '') + '</text>';
  return main;
}

export function coreBars(loads) {
  if (!loads?.length) return '<div class="bars"><span style="background:' + C.track + '"></span></div>';
  const gap = loads.length > 16 ? 1.5 : 3;
  return `<div class="bars" style="gap:${gap}px">${loads.map((l) => `<span style="background:${loadColor(l)}"></span>`).join('')}</div>`;
}

export function usageBar(fractionValue) {
  const w = isNum(fractionValue) ? Math.min(1, Math.max(0, fractionValue)) * 100 : 0;
  return `<div class="usage"><span style="width:${w.toFixed(1)}%"></span></div>`;
}

/** Thin line sparkline over the last samples; nulls break the line. */
export function sparkline(series, color, fixedMax = null) {
  const capacity = 60;
  const values = series ?? [];
  if (values.length < 2) return '<svg class="spark"></svg>';
  const max = Math.max(fixedMax ?? Math.max(0, ...values.filter(isNum)), 1e-3);
  const step = 100 / (capacity - 1);
  const start = 100 - step * (values.length - 1);
  const segments = [];
  let current = [];
  values.forEach((v, i) => {
    if (!isNum(v)) {
      if (current.length) segments.push(current);
      current = [];
      return;
    }
    current.push(`${f2(start + i * step)},${f2(30 - Math.min(1, v / max) * 30)}`);
  });
  if (current.length) segments.push(current);
  const lines = segments.map((s) => `<polyline points="${s.join(' ')}" fill="none" stroke="${color}" stroke-width="1" vector-effect="non-scaling-stroke" stroke-linecap="round"/>`);
  return `<svg class="spark" viewBox="0 0 100 30" preserveAspectRatio="none">${lines.join('')}</svg>`;
}

/** Classic design: thin outline arc gauge with text in the middle. */
export function arcGauge(fractionValue, text, color = C.accent) {
  return ringGauge(fractionValue, color, { stroke: 4.5, inner: centerText(text, { size: 24, fill: text === DASH ? C.label : C.value, weight: 400 }) });
}
