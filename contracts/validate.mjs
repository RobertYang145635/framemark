#!/usr/bin/env node
/**
 * FrameMark contract validator (render-v1 + capabilities v1).
 *
 * Zero dependencies: the repo has no ajv, and this deliberately implements only
 * the JSON Schema keywords used by the two contract files (type, const, enum,
 * properties, required, additionalProperties, items, min/max*, pattern,
 * allOf/anyOf/oneOf/not/if-then-else, local $ref).
 *
 * Usage:
 *   node contracts/validate.mjs                       # gate run: valid fixtures
 *                                                     #   must pass, *.invalid.* must fail
 *   node contracts/validate.mjs --no-expected-invalid # everything must pass (red proof run)
 *   node contracts/validate.mjs --fixtures <dir> --render <schema> --capabilities <schema>
 *
 * Exit code 0 = green, 1 = red.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// args
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) {
        out[key] = next;
        i++;
      } else {
        out[key] = true;
      }
    } else {
      out._.push(a);
    }
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
const fixturesDir = path.resolve(args.fixtures ?? path.join(here, 'fixtures'));
const renderSchemaPath = path.resolve(args.render ?? path.join(here, 'render-v1.schema.json'));
const capabilitiesSchemaPath = path.resolve(args.capabilities ?? path.join(here, 'capabilities.schema.json'));
const expectInvalidFixtures = args['no-expected-invalid'] !== true;

// ---------------------------------------------------------------------------
// minimal JSON Schema evaluator
// ---------------------------------------------------------------------------
const jsonType = (v) => (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v);

function eq(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function resolveRef(root, ref) {
  if (!ref.startsWith('#/')) throw new Error(`unsupported $ref: ${ref}`);
  return ref
    .slice(2)
    .split('/')
    .reduce((node, tok) => {
      const key = tok.replace(/~1/g, '/').replace(/~0/g, '~');
      if (node === undefined || node === null) throw new Error(`unresolvable $ref: ${ref}`);
      return node[key];
    }, root);
}

function check(schema, value, p, root, errors) {
  if (schema === undefined || schema === true) return;
  if (schema === false) {
    errors.push({ p, msg: 'value is not allowed here' });
    return;
  }
  if (typeof schema !== 'object') return;

  if (schema.$ref) {
    check(resolveRef(root, schema.$ref), value, p, root, errors);
  }

  if (schema.type !== undefined) {
    const allowed = Array.isArray(schema.type) ? schema.type : [schema.type];
    const actual = jsonType(value);
    const ok = allowed.some((t) => (t === 'integer' ? actual === 'number' && Number.isInteger(value) : t === actual));
    if (!ok) {
      errors.push({ p, msg: `expected ${allowed.join(' | ')}, got ${actual}` });
      return;
    }
  }

  if (schema.const !== undefined && !eq(schema.const, value)) {
    errors.push({ p, msg: `must equal ${JSON.stringify(schema.const)} (got ${JSON.stringify(value)})` });
  }

  if (schema.enum && !schema.enum.some((v) => eq(v, value))) {
    errors.push({ p, msg: `must be one of ${schema.enum.map((v) => JSON.stringify(v)).join(', ')} (got ${JSON.stringify(value)})` });
  }

  if (jsonType(value) === 'object') {
    const props = schema.properties ?? {};
    for (const key of schema.required ?? []) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) {
        errors.push({ p: `${p}.${key}`, msg: 'required property is missing' });
      }
    }
    const keys = Object.keys(value);
    if (schema.minProperties !== undefined && keys.length < schema.minProperties) {
      errors.push({ p, msg: `needs at least ${schema.minProperties} properties (has ${keys.length})` });
    }
    if (schema.maxProperties !== undefined && keys.length > schema.maxProperties) {
      errors.push({ p, msg: `allows at most ${schema.maxProperties} properties (has ${keys.length})` });
    }
    for (const [key, sub] of Object.entries(props)) {
      if (Object.prototype.hasOwnProperty.call(value, key)) {
        check(sub, value[key], `${p}.${key}`, root, errors);
      }
    }
    if (schema.additionalProperties !== undefined) {
      for (const key of keys) {
        if (Object.prototype.hasOwnProperty.call(props, key)) continue;
        if (schema.additionalProperties === false) {
          errors.push({ p: `${p}.${key}`, msg: 'additional property is not allowed' });
        } else if (typeof schema.additionalProperties === 'object') {
          check(schema.additionalProperties, value[key], `${p}.${key}`, root, errors);
        }
      }
    }
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      errors.push({ p, msg: `needs at least ${schema.minItems} items (has ${value.length})` });
    }
    if (schema.maxItems !== undefined && value.length > schema.maxItems) {
      errors.push({ p, msg: `allows at most ${schema.maxItems} items (has ${value.length})` });
    }
    if (schema.items) {
      value.forEach((item, i) => check(schema.items, item, `${p}[${i}]`, root, errors));
    }
  }

  if (typeof value === 'string') {
    const cp = [...value].length;
    if (schema.minLength !== undefined && cp < schema.minLength) {
      errors.push({ p, msg: `shorter than minLength ${schema.minLength}` });
    }
    if (schema.maxLength !== undefined && cp > schema.maxLength) {
      errors.push({ p, msg: `longer than maxLength ${schema.maxLength}` });
    }
    if (schema.pattern !== undefined && !new RegExp(schema.pattern).test(value)) {
      errors.push({ p, msg: `does not match pattern ${schema.pattern}` });
    }
  }

  if (typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push({ p, msg: `must be >= ${schema.minimum} (got ${value})` });
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push({ p, msg: `must be <= ${schema.maximum} (got ${value})` });
    }
    if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) {
      errors.push({ p, msg: `must be > ${schema.exclusiveMinimum} (got ${value})` });
    }
    if (schema.exclusiveMaximum !== undefined && value >= schema.exclusiveMaximum) {
      errors.push({ p, msg: `must be < ${schema.exclusiveMaximum} (got ${value})` });
    }
    if (schema.multipleOf !== undefined && Math.abs(value / schema.multipleOf - Math.round(value / schema.multipleOf)) > 1e-9) {
      errors.push({ p, msg: `must be a multiple of ${schema.multipleOf}` });
    }
  }

  for (const sub of schema.allOf ?? []) {
    check(sub, value, p, root, errors);
  }

  if (schema.anyOf) {
    const results = schema.anyOf.map((sub) => {
      const e = [];
      check(sub, value, p, root, e);
      return e;
    });
    if (!results.some((e) => e.length === 0)) {
      errors.push({ p, msg: `anyOf: none of ${results.length} branches matched` });
      const best = results.reduce((a, b) => (b.length < a.length ? b : a));
      errors.push(...best);
    }
  }

  if (schema.oneOf) {
    const results = schema.oneOf.map((sub) => {
      const e = [];
      check(sub, value, p, root, e);
      return e;
    });
    const matched = results.filter((e) => e.length === 0).length;
    if (matched !== 1) {
      errors.push({ p, msg: `oneOf: ${matched} of ${results.length} branches matched (need exactly 1)` });
      if (matched === 0) {
        const best = results.reduce((a, b) => (b.length < a.length ? b : a));
        errors.push(...best.map((e) => ({ p: e.p, msg: `closest branch: ${e.msg}` })));
      }
    }
  }

  if (schema.not) {
    const e = [];
    check(schema.not, value, p, root, e);
    if (e.length === 0) errors.push({ p, msg: 'must NOT match the not-schema' });
  }

  if (schema.if) {
    const cond = [];
    check(schema.if, value, p, root, cond);
    if (cond.length === 0 && schema.then) check(schema.then, value, p, root, errors);
    if (cond.length > 0 && schema.else) check(schema.else, value, p, root, errors);
  }
}

function validateDoc(schema, doc) {
  const errors = [];
  check(schema, doc, '$', schema, errors);
  return errors;
}

/** Validate a document against a $ref inside a root schema (keeps the root for $ref resolution). */
function validateRef(rootSchema, ref, doc) {
  const errors = [];
  check(resolveRef(rootSchema, ref), doc, '$', rootSchema, errors);
  return errors;
}

// ---------------------------------------------------------------------------
// semantic checks (rules the JSON structure alone cannot express)
// ---------------------------------------------------------------------------
const SCHEMA_KEYS = new Set([
  'styleid', 'sections', 'controls', 'label', 'default', 'min', 'max', 'step',
  'options', 'gated', 'visible', 'type', 'id', 'value', 'schemaVersion', 'cards',
]);

function looksLikeRawKey(text) {
  const t = text.trim();
  if (SCHEMA_KEYS.has(t.toLowerCase())) return true;
  if (t.includes('_') || t.includes('.') || t.includes('$')) return true;
  return /^[a-z][A-Za-z0-9]*$/.test(t);
}

function semanticCapabilities(doc) {
  const errors = [];
  const styleIds = new Set();
  for (const [ci, card] of doc.cards.entries()) {
    const cp = `$.cards[${ci}]`;
    if (styleIds.has(card.styleId)) errors.push({ p: `${cp}.styleId`, msg: `duplicate styleId ${card.styleId}` });
    styleIds.add(card.styleId);
    const controlIds = new Set();
    const sectionIds = new Set();
    for (const [si, section] of card.sections.entries()) {
      const sp = `${cp}.sections[${si}]`;
      if (sectionIds.has(section.id)) errors.push({ p: `${sp}.id`, msg: `duplicate section id ${section.id}` });
      sectionIds.add(section.id);
      if (looksLikeRawKey(section.label)) {
        errors.push({ p: `${sp}.label`, msg: `label "${section.label}" looks like a schema key / field id, not user copy` });
      }
      for (const [kt, control] of section.controls.entries()) {
        const kp = `${sp}.controls[${kt}]`;
        if (controlIds.has(control.id)) errors.push({ p: `${kp}.id`, msg: `duplicate control id ${control.id}` });
        controlIds.add(control.id);
        if (looksLikeRawKey(control.label)) {
          errors.push({ p: `${kp}.label`, msg: `label "${control.label}" looks like a schema key / field id, not user copy` });
        }
        for (const [oi, option] of (control.options ?? []).entries()) {
          if (looksLikeRawKey(option.label)) {
            errors.push({ p: `${kp}.options[${oi}].label`, msg: `option label "${option.label}" looks like a raw value, not user copy` });
          }
        }
      }
    }
  }
  return errors;
}

function semanticResponse(doc, p = '$') {
  const errors = [];
  const img = doc.image;
  const maxEdge = Math.max(img.w, img.h);
  if (img.rgba !== undefined) {
    const bytes = Buffer.from(img.rgba, 'base64').length;
    const expected = img.w * img.h * 4;
    if (bytes !== expected) {
      errors.push({ p: `${p}.image.rgba`, msg: `decoded ${bytes} bytes, expected w*h*4 = ${expected}` });
    }
    if (maxEdge > 512) {
      errors.push({ p: `${p}.image.rgba`, msg: `inline rgba is limited to previews with max edge <= 512 px (got ${maxEdge}); send a path instead` });
    }
  }
  if (doc.photoRect.w <= 0 || doc.photoRect.h <= 0) {
    errors.push({ p: `${p}.photoRect`, msg: 'photoRect must have positive w/h' });
  }
  const ids = new Set();
  for (const [i, slot] of doc.textSlots.entries()) {
    if (ids.has(slot.id)) errors.push({ p: `${p}.textSlots[${i}].id`, msg: `duplicate text slot id ${slot.id}` });
    ids.add(slot.id);
  }
  return errors;
}

function semanticPair(req, res, id) {
  const errors = [];
  const p = `pair ${id}`;
  if (req.mode === 'export') {
    if (res.image.path === undefined) {
      errors.push({ p, msg: 'export response must return image.path (no inline bytes for full-size output)' });
    }
    if (req.band?.h !== undefined && res.image.h !== req.band.h) {
      errors.push({ p: `${p}.image.h`, msg: `band request h=${req.band.h} but response image h=${res.image.h}` });
    }
  }
  if (req.mode === 'preview' && req.maxEdge !== undefined) {
    const edge = Math.max(res.image.w, res.image.h);
    if (edge > req.maxEdge) {
      errors.push({ p: `${p}.image`, msg: `preview maxEdge=${req.maxEdge} but response edge=${edge}` });
    }
  }
  if (req.design?.canvas?.w && req.mode === 'export' && !req.band && res.image.w !== req.design.canvas.w) {
    errors.push({ p: `${p}.image.w`, msg: `full export width ${res.image.w} != canvas width ${req.design.canvas.w}` });
  }
  const designSlots = (req.design?.elements ?? []).filter((e) => e.kind === 'text' && e.slot).map((e) => e.slot);
  if (designSlots.length > 0) {
    for (const [i, slot] of res.textSlots.entries()) {
      if (!designSlots.includes(slot.id)) {
        errors.push({ p: `${p}.textSlots[${i}].id`, msg: `slot id "${slot.id}" is not a text slot in the design (${designSlots.join(', ')})` });
      }
    }
  }
  return errors;
}

// ---------------------------------------------------------------------------
// run
// ---------------------------------------------------------------------------
const lines = [];
const log = (s = '') => {
  lines.push(s);
  console.log(s);
};

let failures = 0;
let passes = 0;
let expectedReds = 0;

function loadJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function report(file, kind, errors, isExpectedInvalid) {
  if (errors.length === 0) {
    if (isExpectedInvalid && expectInvalidFixtures) {
      failures++;
      log(`[FAIL] ${file} (${kind}) — expected-red fixture PASSED; the validator cannot see the seeded defect`);
    } else {
      passes++;
      log(`[PASS] ${file} (${kind})`);
    }
    return;
  }
  if (isExpectedInvalid && expectInvalidFixtures) {
    expectedReds++;
    log(`[RED ] ${file} (${kind}) — correctly rejected, ${errors.length} violation(s):`);
    for (const e of errors) log(`         ${e.p}: ${e.msg}`);
    return;
  }
  failures++;
  log(`[FAIL] ${file} (${kind}) — ${errors.length} violation(s):`);
  for (const e of errors) log(`         ${e.p}: ${e.msg}`);
}

log('FrameMark contract validator');
log(`  render schema:       ${renderSchemaPath}`);
log(`  capabilities schema: ${capabilitiesSchemaPath}`);
log(`  fixtures:            ${fixturesDir}`);
log(`  mode:                ${expectInvalidFixtures ? 'gate (expected-red fixtures must be rejected)' : 'all-valid (every fixture must pass)'}`);
log('');

let renderSchema;
let capSchema;
try {
  renderSchema = loadJson(renderSchemaPath);
  capSchema = loadJson(capabilitiesSchemaPath);
} catch (err) {
  console.error(`FATAL: cannot load schema files: ${err.message}`);
  process.exit(1);
}

const files = fs.readdirSync(fixturesDir).filter((f) => f.endsWith('.json')).sort();
if (files.length === 0) {
  console.error(`FATAL: no fixtures in ${fixturesDir}`);
  process.exit(1);
}

const pairs = new Map();
const parsed = new Map();

for (const file of files) {
  const full = path.join(fixturesDir, file);
  const isExpectedInvalid = /(^|\.)invalid\./i.test(file);
  let doc;
  try {
    doc = loadJson(full);
  } catch (err) {
    report(file, 'unparseable', [{ p: '$', msg: `invalid JSON: ${err.message}` }], isExpectedInvalid);
    continue;
  }
  parsed.set(file, doc);

  if (file === 'capabilities.example.json') {
    const errors = [...validateDoc(capSchema, doc), ...semanticCapabilities(doc)];
    report(file, 'capabilities', errors, isExpectedInvalid);
    continue;
  }

  const isRequest = file.endsWith('.request.json');
  const isResponse = file.endsWith('.response.json');
  if (!isRequest && !isResponse) {
    report(file, 'unknown', [{ p: '$', msg: 'fixture name must end with .request.json or .response.json' }], isExpectedInvalid);
    continue;
  }

  const kind = isRequest ? 'request' : 'response';
  const def = isRequest ? '#/$defs/renderRequest' : '#/$defs/renderResponse';
  const errors = validateRef(renderSchema, def, doc);
  // The root oneOf must accept it too (proves the file is a valid contract document).
  // Branch details are already reported above, so a failed root check adds one line.
  const rootErrors = validateDoc(renderSchema, doc);
  if (rootErrors.length > 0) {
    errors.push({ p: '$', msg: 'root oneOf rejected the document (not a valid v1 request/response)' });
  }
  if (isResponse) errors.push(...semanticResponse(doc));
  report(file, kind, errors, isExpectedInvalid);

  const m = file.match(/^(pair-\d+)\.(request|response)\.json$/);
  if (m) {
    const entry = pairs.get(m[1]) ?? {};
    entry[m[2]] = doc;
    pairs.set(m[1], entry);
  }
}

log('');
log(`Pairs found: ${pairs.size}`);
if (pairs.size !== 10) {
  failures++;
  log(`[FAIL] expected exactly 10 request/response pairs, found ${pairs.size}`);
}
for (const [id, pair] of [...pairs.entries()].sort()) {
  if (!pair.request || !pair.response) {
    failures++;
    log(`[FAIL] ${id}: missing ${!pair.request ? 'request' : 'response'} fixture`);
    continue;
  }
  const errors = semanticPair(pair.request, pair.response, id);
  if (errors.length === 0) {
    passes++;
    log(`[PASS] ${id} pair semantics (mode/geometry/slot ids)`);
  } else {
    failures++;
    log(`[FAIL] ${id} pair semantics — ${errors.length} violation(s):`);
    for (const e of errors) log(`         ${e.p}: ${e.msg}`);
  }
}

log('');
log(`Summary: ${files.length} fixture files, ${passes} pass, ${expectedReds} expected-red, ${failures} fail`);
if (failures === 0) {
  log('RESULT: GREEN');
  process.exit(0);
}
log('RESULT: RED');
process.exit(1);
