import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as F from '../js/format.js';

test('nulls render as dash', () => {
  for (const fn of [F.percent, F.clock, F.power, F.rate, F.mbps, F.size, F.bytes, F.ghz, F.fps]) {
    assert.equal(fn(null), F.DASH);
  }
  assert.equal(F.temp(undefined, false), F.DASH);
  assert.equal(F.memPair(null, null), F.DASH);
});

test('units match the Android app', () => {
  assert.equal(F.rate(12000), '11.7 KB/s');
  assert.equal(F.rate(3_400_000), '3.24 MB/s');
  assert.equal(F.mbps(6_400_000), '51.2');
  assert.equal(F.mbps(15_000_000), '120');
  assert.equal(F.size(719 * 1024), '719 GB');
  assert.equal(F.size(1.8 * 1024 * 1024), '1.8 TB');
  assert.equal(F.bytes(18.2 * 1024 ** 3), '18.2 GB');
  assert.equal(F.temp(61, true), '142°F');
  assert.equal(F.clock(4880), '4.88 GHz');
  assert.equal(F.tempLevel(80.1), 'hot');
  assert.equal(F.tempLevel(60), 'warm');
});

test('session helpers', () => {
  let r = { min: null, max: null };
  for (const v of [100, null, 900, 50]) r = F.minMax(r, v);
  assert.deepEqual(r, { min: 50, max: 900 });
  let s = [];
  for (let i = 0; i < 75; i++) s = F.pushSeries(s, i);
  assert.equal(s.length, 60);
  assert.equal(s[59], 74);
  assert.equal(F.escapeHtml('<a&"b">'), '&lt;a&amp;&quot;b&quot;&gt;');
});
