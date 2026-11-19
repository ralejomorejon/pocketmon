// Value formatting shared by both dashboard designs. Mirrors the Android app's Format object.
export const DASH = '—';

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const round = (v) => Math.round(v);
const fixed = (v, d) => v.toFixed(d);

export function tempNumber(c, fahrenheit) {
  if (!isNum(c)) return DASH;
  return String(round(fahrenheit ? c * 9 / 5 + 32 : c));
}

export function temp(c, fahrenheit) {
  return isNum(c) ? `${tempNumber(c, fahrenheit)}°${fahrenheit ? 'F' : 'C'}` : DASH;
}

/** 'normal' | 'warm' | 'hot' | null */
export function tempLevel(c) {
  if (!isNum(c)) return null;
  if (c > 80) return 'hot';
  if (c >= 60) return 'warm';
  return 'normal';
}

export const percent = (v) => (isNum(v) ? `${round(v)}%` : DASH);
export const percentNumber = (v) => (isNum(v) ? String(round(v)) : DASH);

export function clock(mhz) {
  if (!isNum(mhz)) return DASH;
  return mhz >= 1000 ? `${fixed(mhz / 1000, 2)} GHz` : `${round(mhz)} MHz`;
}

export const ghz = (mhz) => (isNum(mhz) ? fixed(mhz / 1000, 2) : DASH);

export function power(w) {
  if (!isNum(w)) return DASH;
  return Math.abs(w) < 10 ? `${fixed(w, 1)} W` : `${round(w)} W`;
}

export const watts = (w) => (isNum(w) ? String(round(w)) : DASH);
export const rpm = (v) => (isNum(v) ? `${round(v)} rpm` : DASH);
export const fps = (v) => (isNum(v) ? String(round(v)) : DASH);

/** Megabytes (as reported by the agent) to a compact GB number string. */
export const memGb = (mb) => (isNum(mb) ? fixed(mb / 1024, 1) : DASH);
export const mem = (mb) => (isNum(mb) ? `${memGb(mb)} GB` : DASH);

export function memPair(used, total) {
  if (!isNum(used) && !isNum(total)) return DASH;
  return `${memGb(used)} / ${memGb(total)} GB`;
}

/** Bytes per second with auto units (1024 base). */
export function rate(bps) {
  if (!isNum(bps)) return DASH;
  const units = ['B/s', 'KB/s', 'MB/s', 'GB/s'];
  let v = Math.max(0, bps);
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  if (i === 0) return `${round(v)} ${units[i]}`;
  if (v < 10) return `${fixed(v, 2)} ${units[i]}`;
  if (v < 100) return `${fixed(v, 1)} ${units[i]}`;
  return `${round(v)} ${units[i]}`;
}

/** Bytes per second as megabits per second. */
export function mbps(bps) {
  if (!isNum(bps)) return DASH;
  const v = Math.max(0, bps) * 8 / 1e6;
  return v < 100 ? fixed(v, 1) : String(round(v));
}

/** Megabytes to "719 GB" / "1.8 TB". */
export function size(mb) {
  if (!isNum(mb)) return DASH;
  if (mb >= 1024 * 1024) return `${fixed(mb / (1024 * 1024), 1)} TB`;
  if (mb >= 1024) return `${round(mb / 1024)} GB`;
  return `${round(mb)} MB`;
}

/** Bytes to "18.2 GB" / "640 MB". */
export function bytes(b) {
  if (!isNum(b)) return DASH;
  const gb = 1024 ** 3;
  if (b >= 1024 * gb) return `${fixed(b / (1024 * gb), 1)} TB`;
  if (b >= gb) return `${fixed(b / gb, 1)} GB`;
  return `${round(b / (1024 * 1024))} MB`;
}

export function fraction(used, total) {
  if (!isNum(used) || !isNum(total) || total <= 0) return null;
  return Math.min(1, Math.max(0, used / total));
}

/** Range of values seen this session; nulls ignored. */
export function minMax(prev, v) {
  if (!isNum(v)) return prev;
  return { min: isNum(prev.min) ? Math.min(prev.min, v) : v, max: isNum(prev.max) ? Math.max(prev.max, v) : v };
}

/** Appends to a fixed-size series (oldest first). */
export function pushSeries(series, v, capacity = 60) {
  const next = series.length >= capacity ? series.slice(series.length - capacity + 1) : series.slice();
  next.push(isNum(v) ? v : null);
  return next;
}

export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
