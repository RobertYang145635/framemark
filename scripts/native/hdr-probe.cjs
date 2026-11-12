#!/usr/bin/env node
'use strict';

/**
 * Read-only probe for Ultra HDR (gain map) JPEGs.
 *
 * It answers the three questions the export work needs, for a real file:
 *   1. where is the gain map (MPF Index IFD, CIPA DC-x 007),
 *   2. what does the gain map's XMP say (`hdrgm:*`, Android Ultra HDR v1.1),
 *   3. what encoded value means "0 dB" in the gain map (the border sentinel).
 *
 * The sentinel is the whole reason this tool exists. A frame app fills the
 * border of the gain map with it; filling 0 instead would darken the border by
 * up to a full stop on an HDR display. The formula is the encode half of the
 * spec, evaluated at pixel_gain = 1 (log2 = 0):
 *
 *   normalized = (0 - GainMapMin) / (GainMapMax - GainMapMin)
 *   recovery   = clamp(normalized, 0, 1) ^ Gamma
 *   encoded    = floor(recovery * 255 + 0.5)
 *
 * With no arguments, the tool reads the samples named by FM_HDR_SAMPLES (a
 * directory) and prints one JSON object per file. `--check <file>` parses a
 * file and exits non-zero when a structural invariant is broken, so the same
 * code can verify our own output in the release pipeline.
 *
 * No dependencies: the release pipeline runs it without npm install.
 */

const fs = require('fs');
const path = require('path');

// ------------------------------------------------------------------ bytes

const be16 = (b, o) => (b[o] << 8) | b[o + 1];
const be32 = (b, o) => ((b[o] << 24) >>> 0) + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3];

/** Marker segments of a JPEG starting at `start`, up to (and including) SOS. */
function segments (buf, start) {
  if (buf[start] !== 0xFF || buf[start + 1] !== 0xD8) throw new Error(`not a JPEG at ${start}`);
  const out = [];
  let i = start + 2;
  while (i < buf.length - 1) {
    if (buf[i] !== 0xFF) throw new Error(`marker expected at ${i}, found 0x${buf[i].toString(16)}`);
    const marker = buf[i + 1];
    if (marker === 0xD9) { out.push({ marker, offset: i, length: 2 }); return out; }
    if (marker === 0xDA) { out.push({ marker, offset: i, length: 2 }); return out; }
    if (marker === 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
    if (i + 4 > buf.length) throw new Error(`truncated segment at ${i}`);
    const length = be16(buf, i + 2);
    if (length < 2 || i + 2 + length > buf.length) throw new Error(`bad segment length at ${i}`);
    out.push({
      marker,
      offset: i,
      length,
      payloadStart: i + 4,
      payloadEnd: i + 2 + length,
      payload: buf.subarray(i + 4, i + 2 + length),
    });
    i += 2 + length;
  }
  throw new Error('no SOS before end of file');
}

const ascii = (payload, n) => payload.subarray(0, n).toString('latin1');
const XMP_PREFIX = 'http://ns.adobe.com/xap/1.0/';
const ISO_URN = 'urn:iso:std:iso:ts:21496:-1';

/** Frame dimensions from the first SOFn segment. */
function frameSize (segs) {
  for (const s of segs) {
    if (s.payload && s.marker >= 0xC0 && s.marker <= 0xCF &&
        s.marker !== 0xC4 && s.marker !== 0xC8 && s.marker !== 0xCC) {
      const p = s.payload;
      return { height: be16(p, 1), width: be16(p, 3), components: p[5], bits: p[0], sof: s.marker };
    }
  }
  return null;
}

// ---------------------------------------------------------------- MPF (CIPA)

/**
 * Multi-Picture Format index.
 *
 * `MP Entry` offsets are relative to the TIFF header, not to the file, and the
 * primary image entry stores 0. Both facts are easy to get wrong: with the
 * Lightroom sample, `entry[1].offset + tiffStart` is exactly the primary
 * image's byte length, and adding the file start instead lands 56 KB early.
 */
function parseMpf (buf, segs) {
  const seg = segs.find((s) => s.payload && ascii(s.payload, 4) === 'MPF\x00');
  if (!seg) return null;
  const p = seg.payload;
  const tiffStart = seg.payloadStart + 4;
  const order = p.subarray(4, 6).toString('latin1');
  if (order !== 'II' && order !== 'MM') throw new Error(`MPF endianness '${order}'`);
  const le = order === 'II';
  const r16 = le ? (b, o) => b[o] | (b[o + 1] << 8) : be16;
  const r32 = le
    ? (b, o) => ((b[o + 3] << 24) >>> 0) + (b[o + 2] << 16) + (b[o + 1] << 8) + b[o]
    : be32;
  if (p[6] !== 0 || (le ? p[7] !== 0x2A : p[7] !== 0x2A)) throw new Error('MPF TIFF magic missing');

  const ifd0 = r32(p, 8) + tiffStart;
  const count = r16(buf, ifd0);
  const tags = {};
  for (let e = 0; e < count; e++) {
    const at = ifd0 + 2 + e * 12;
    tags[r16(buf, at)] = at;
  }
  const entriesAt = tags[0xB002] === undefined
    ? null
    : r32(buf, tags[0xB002] + 8) + tiffStart;
  const imageCount = tags[0xB001] === undefined ? 0 : r32(buf, tags[0xB001] + 8);
  if (entriesAt === null) return { imageCount, tiffStart, order, images: [] };

  const images = [];
  for (let i = 0; i < imageCount; i++) {
    const at = entriesAt + i * 16;
    images.push({
      attributes: r32(buf, at),
      size: r32(buf, at + 4),
      offset: r32(buf, at + 8),
      // A zero offset is how the primary entry points at the file itself.
      absolute: i === 0 && r32(buf, at + 8) === 0 ? 0 : r32(buf, at + 8) + tiffStart,
    });
  }
  return { imageCount, tiffStart, order, images };
}

// -------------------------------------------------------------------- XMP

const real = (v) => {
  const n = Number.parseFloat(v);
  return Number.isFinite(n) ? n : null;
};

/**
 * `hdrgm:*` properties. Every numeric property may be a single Real or an
 * ordered array of three Reals (per channel), so everything comes back as an
 * array of 1 or 3 numbers in file order.
 */
function parseHdrgm (xmpText) {
  if (typeof xmpText !== 'string' || xmpText.length === 0) return null;
  const props = {};
  for (const m of xmpText.matchAll(/hdrgm:([A-Za-z0-9_]+)\s*=\s*"([^"]*)"/g)) {
    const name = m[1];
    if (props[name] === undefined) props[name] = m[2];
  }
  for (const key of ['GainMapMin', 'GainMapMax', 'Gamma', 'OffsetSDR', 'OffsetHDR']) {
    const re = new RegExp(`<hdrgm:${key}>([\\s\\S]*?)</hdrgm:${key}>`);
    const block = xmpText.match(re);
    if (!block) continue;
    const vals = [...block[1].matchAll(/<rdf:li>([^<]*)<\/rdf:li>/g)].map((m) => real(m[1]));
    if (vals.length > 0 && vals.every((v) => v !== null)) props[key] = vals.map(String);
  }
  const pick = (key) => {
    const raw = props[key];
    if (raw === undefined) return null;
    return Array.isArray(raw) ? raw.map(real) : [real(raw)];
  };
  return {
    raw: props,
    version: props.Version ?? null,
    baseRenditionIsHDR: props.BaseRenditionIsHDR ?? null,
    gainMapMin: pick('GainMapMin'),
    gainMapMax: pick('GainMapMax'),
    gamma: pick('Gamma'),
    offsetSdr: pick('OffsetSDR'),
    offsetHdr: pick('OffsetHDR'),
    hdrCapacityMin: pick('HDRCapacityMin'),
    hdrCapacityMax: pick('HDRCapacityMax'),
  };
}

/** Per-channel value, broadcast from the single-Real form the spec allows. */
function channels (value, fallback) {
  if (!Array.isArray(value) || value.length === 0) return [fallback, fallback, fallback];
  if (value.length === 1) return [value[0], value[0], value[0]];
  if (value.length !== 3) throw new Error(`expected 1 or 3 channels, got ${value.length}`);
  return value;
}

/**
 * Encoded gain-map value that means 0 dB (HDR == SDR), per channel.
 * `floor(recovery * 255 + 0.5)` is the spec's rounding, not Math.round.
 */
function sentinel (meta) {
  const min = channels(meta.gainMapMin, 0);
  const max = channels(meta.gainMapMax, null);
  const gamma = channels(meta.gamma, 1);
  if (max.some((v) => v === null)) throw new Error('GainMapMax is required');
  return min.map((lo, i) => {
    const hi = max[i];
    const span = hi - lo;
    if (!(span > 0)) throw new Error(`GainMapMax must exceed GainMapMin (channel ${i})`);
    if (!(gamma[i] > 0)) throw new Error(`Gamma must be > 0 (channel ${i})`);
    const normalized = (0 - lo) / span;
    const clamped = Math.min(1, Math.max(0, normalized));
    const recovery = Math.pow(clamped, gamma[i]);
    return Math.floor(recovery * 255 + 0.5);
  });
}

// ---------------------------------------------------------------- analysis

function xmpOf (segs) {
  for (const s of segs) {
    if (s.marker === 0xE1 && s.payload && ascii(s.payload, XMP_PREFIX.length) === XMP_PREFIX) {
      return s.payload.subarray(XMP_PREFIX.length).toString('utf8');
    }
  }
  return null;
}

function isoPayloadOf (segs) {
  for (const s of segs) {
    if (s.marker === 0xE2 && s.payload && ascii(s.payload, ISO_URN.length) === ISO_URN) {
      return { bytes: s.payload.length, hasData: s.payload.length > 32 };
    }
  }
  return null;
}

function analyze (file) {
  const buf = fs.readFileSync(file);
  const baseSegs = segments(buf, 0);
  const baseXmp = xmpOf(baseSegs);
  const baseHdrgm = parseHdrgm(baseXmp);
  const mpf = parseMpf(buf, baseSegs);
  if (!mpf || mpf.images.length < 2) throw new Error('no secondary image in the MPF index');

  const gain = mpf.images[1];
  if (gain.absolute + gain.size > buf.length) {
    throw new Error(`gain map runs past EOF (${gain.absolute}+${gain.size} > ${buf.length})`);
  }
  const gainBuf = buf.subarray(gain.absolute, gain.absolute + gain.size);
  const gainSegs = segments(gainBuf, 0);
  const gainXmp = xmpOf(gainSegs);
  const meta = parseHdrgm(gainXmp);
  if (!meta || meta.gainMapMax === null) throw new Error('gain map XMP has no hdrgm:GainMapMax');

  const container = baseXmp && /Item:Semantic\s*=\s*"GainMap"/.test(baseXmp)
    ? { present: true, length: (() => {
        const m = baseXmp.match(/Item:Semantic\s*=\s*"GainMap"[\s\S]{0,200}?Item:Length\s*=\s*"(\d+)"/);
        return m ? Number(m[1]) : null;
      })() }
    : { present: false, length: null };

  return {
    file: path.basename(file),
    bytes: buf.length,
    primary: { size: mpf.images[0].size, frame: frameSize(baseSegs), xmp: baseHdrgm ? baseHdrgm.raw : null },
    gainMap: {
      offset: gain.absolute,
      size: gain.size,
      frame: frameSize(gainSegs),
      metadata: meta.raw,
      parsed: {
        gainMapMin: meta.gainMapMin,
        gainMapMax: meta.gainMapMax,
        gamma: meta.gamma,
        offsetSdr: meta.offsetSdr,
        offsetHdr: meta.offsetHdr,
        hdrCapacityMin: meta.hdrCapacityMin,
        hdrCapacityMax: meta.hdrCapacityMax,
      },
    },
    container,
    iso: { primary: isoPayloadOf(baseSegs), gainMap: isoPayloadOf(gainSegs) },
    /** The value a border must carry so it renders identically in SDR and HDR. */
    sentinel: sentinel(meta),
  };
}

// ------------------------------------------------------------------- check

/** Structural invariants of a conforming file; used on our own output too. */
function check (report) {
  const problems = [];
  const { primary, gainMap, container, iso, sentinel: s } = report;
  if (!primary.frame) problems.push('primary has no SOF');
  if (!gainMap.frame) problems.push('gain map has no SOF');
  if (gainMap.offset === 0) problems.push('gain map offset is 0');
  if (gainMap.offset + gainMap.size > report.bytes) problems.push('gain map runs past EOF');
  if (!gainMap.metadata.GainMapMax) problems.push('gain map XMP missing hdrgm:GainMapMax');
  if (container.present && container.length !== null && container.length !== gainMap.size) {
    problems.push(`Item:Length ${container.length} != gain map size ${gainMap.size}`);
  }
  if (!iso.gainMap) problems.push('gain map has no ISO 21496-1 segment');
  for (const [i, v] of s.entries()) {
    if (!(v >= 0 && v <= 255)) problems.push(`sentinel channel ${i} out of range: ${v}`);
    if (v === 0 || v === 255) problems.push(`sentinel channel ${i} is ${v}: border would not render as 0 dB`);
  }
  return problems;
}

// -------------------------------------------------------------------- CLI

function main (argv) {
  const args = argv.slice(2);
  const checkMode = args[0] === '--check';
  const targets = checkMode ? args.slice(1) : args;

  let files = targets;
  if (files.length === 0) {
    const dir = process.env.FM_HDR_SAMPLES;
    if (!dir) {
      console.error('usage: hdr-probe.cjs [--check] <file.jpg>...  (or set FM_HDR_SAMPLES)');
      return 2;
    }
    files = [];
    const walk = (d) => {
      for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, entry.name);
        if (entry.isDirectory()) walk(p);
        else if (/\.jpe?g$/i.test(entry.name)) files.push(p);
      }
    };
    walk(dir);
  }

  let failures = 0;
  for (const file of files) {
    let report;
    try {
      report = analyze(file);
    } catch (error) {
      failures++;
      console.error(JSON.stringify({ file: path.basename(file), error: error.message }));
      continue;
    }
    if (!checkMode) {
      console.log(JSON.stringify(report, null, 2));
      continue;
    }
    const problems = check(report);
    if (problems.length) failures++;
    console.log(JSON.stringify({
      file: report.file,
      ok: problems.length === 0,
      problems,
      primary: report.primary.frame ? `${report.primary.frame.width}x${report.primary.frame.height}` : null,
      gainMap: report.gainMap.frame ? `${report.gainMap.frame.width}x${report.gainMap.frame.height}` : null,
      gainMapBytes: report.gainMap.size,
      container: report.container.present ? report.container.length : null,
      isoBytes: report.iso.gainMap ? report.iso.gainMap.bytes : null,
      sentinel: report.sentinel,
    }));
  }
  return failures === 0 ? 0 : 1;
}

if (require.main === module) process.exitCode = main(process.argv);

module.exports = { analyze, check, sentinel, parseHdrgm, parseMpf, segments, frameSize };
