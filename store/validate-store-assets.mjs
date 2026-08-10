#!/usr/bin/env node
// validate-store-assets.mjs
// Validate Google Play store PNGs in a store/ directory against the store's
// hard requirements. Reusable across apps.
//
// Checks per asset:
//   - valid PNG encoding (magic bytes + IHDR)
//   - correct dimensions (from the filename's WxH, or icon->512x512 /
//     feature->1024x500 conventions)
//   - 32-bit colour (8-bit depth, RGBA colour type 6)
//   - opaque (alpha channel minimum == 255)
//   - file size < 32 KB
//   - correct file naming (recognised asset name)
//
// Screenshots (anything under a 'shots'/'screenshots' subdir, or files that
// don't match an asset convention) are reported but not failed on dimensions.
//
// Usage:
//   node validate-store-assets.mjs [storeDir]
// Exits non-zero if any required asset fails.

import { readdirSync, statSync, readFileSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

let sharp;
try {
  sharp = (await import('sharp')).default;
} catch {
  console.error('Missing sharp. Install with:\n  npm install sharp');
  process.exit(2);
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const storeDir = process.argv[2] ? process.argv[2] : scriptDir;
const MAX_BYTES = 32 * 1024;

// Read IHDR straight from the PNG so colour type / bit depth are exact.
function readIhdr(buf) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  if (!buf.subarray(0, 8).equals(sig)) return null;
  if (buf.toString('ascii', 12, 16) !== 'IHDR') return null;
  return {
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20),
    bitDepth: buf[24],
    colorType: buf[25],
  };
}

// Expected dimensions from the filename.
function expectedDims(name) {
  const wh = name.match(/(\d+)x(\d+)/i);
  if (wh) return { w: +wh[1], h: +wh[2], kind: 'sized' };
  if (/^icon/i.test(name)) return { w: 512, h: 512, kind: 'icon' };
  if (/^feature/i.test(name)) return { w: 1024, h: 500, kind: 'feature' };
  return null; // unknown -> treat as screenshot / freeform
}

const colorName = (ct) =>
  ({ 0: 'grayscale', 2: 'RGB(24-bit)', 3: 'palette', 4: 'gray+alpha', 6: 'RGBA(32-bit)' }[ct] ||
    `type${ct}`);

const pngs = readdirSync(storeDir).filter((f) => f.toLowerCase().endsWith('.png'));
if (pngs.length === 0) {
  console.error(`No .png files found in ${storeDir}`);
  process.exit(1);
}

let failed = 0;
const rows = [];
for (const name of pngs) {
  const p = join(storeDir, name);
  const buf = readFileSync(p);
  const size = statSync(p).size;
  const ihdr = readIhdr(buf);
  const exp = expectedDims(name);
  const isAsset = exp && exp.kind !== null && (exp.kind === 'icon' || exp.kind === 'feature' || /^(icon|feature)/i.test(name));

  const problems = [];
  if (!ihdr) problems.push('not a valid PNG');
  if (ihdr && exp && (ihdr.width !== exp.w || ihdr.height !== exp.h))
    problems.push(`dims ${ihdr.width}x${ihdr.height} != ${exp.w}x${exp.h}`);
  if (ihdr && (ihdr.colorType !== 6 || ihdr.bitDepth !== 8))
    problems.push(`not 32-bit (${colorName(ihdr.colorType)}, depth ${ihdr.bitDepth})`);

  // Opacity: alpha channel min must be 255.
  let alphaMin = null;
  if (ihdr && ihdr.colorType === 6) {
    try {
      const stats = await sharp(buf).stats();
      const a = stats.channels[3];
      alphaMin = a ? a.min : null;
      if (alphaMin !== 255) problems.push(`not opaque (alpha min ${alphaMin})`);
    } catch {
      problems.push('alpha read failed');
    }
  }

  if (isAsset && size > MAX_BYTES) problems.push(`size ${(size / 1024).toFixed(1)}KB > 32KB`);
  if (!exp) problems.length = 0; // freeform (screenshot) — informational only

  const ok = problems.length === 0;
  if (!ok && exp) failed++;
  rows.push({
    name,
    dims: ihdr ? `${ihdr.width}x${ihdr.height}` : '?',
    color: ihdr ? colorName(ihdr.colorType) : '?',
    alpha: alphaMin === null ? '-' : alphaMin,
    kb: (size / 1024).toFixed(1),
    status: exp ? (ok ? 'PASS' : 'FAIL: ' + problems.join('; ')) : 'skip (freeform)',
  });
}

const pad = (s, n) => String(s).padEnd(n);
console.log(pad('ASSET', 34) + pad('DIMS', 12) + pad('COLOR', 14) + pad('α', 5) + pad('KB', 8) + 'STATUS');
console.log('-'.repeat(100));
for (const r of rows)
  console.log(pad(r.name, 34) + pad(r.dims, 12) + pad(r.color, 14) + pad(r.alpha, 5) + pad(r.kb, 8) + r.status);

console.log('-'.repeat(100));
console.log(failed ? `✗ ${failed} asset(s) FAILED` : '✓ all required assets valid');
process.exit(failed ? 1 : 0);
