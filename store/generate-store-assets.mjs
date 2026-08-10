#!/usr/bin/env node
// generate-store-assets.mjs
// Render every *.svg in a store/ directory to a Google-Play-ready PNG:
//   - dimensions taken from the SVG's own width/height
//   - 32-bit RGBA, opaque (the SVG must paint a full-frame opaque background)
//   - re-encoded and size-checked (< 32 KB for icon/feature graphics)
//
// Reusable across apps. Node resolves @resvg/resvg-js and sharp by walking up
// from this file, so it works from any repo that has them installed (or that
// nests under a dir which does).
//
// Usage:
//   node generate-store-assets.mjs [storeDir]
// storeDir defaults to the directory this script lives in.

import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

let Resvg, sharp;
try {
  ({ Resvg } = await import('@resvg/resvg-js'));
  sharp = (await import('sharp')).default;
} catch (e) {
  console.error('Missing render deps. Install with:\n  npm install @resvg/resvg-js sharp');
  process.exit(2);
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const storeDir = process.argv[2] ? process.argv[2] : scriptDir;

const svgs = readdirSync(storeDir).filter((f) => f.toLowerCase().endsWith('.svg'));
if (svgs.length === 0) {
  console.error(`No .svg files found in ${storeDir}`);
  process.exit(1);
}

let failures = 0;
for (const svgName of svgs) {
  const svgPath = join(storeDir, svgName);
  const svg = readFileSync(svgPath, 'utf8');

  // Render at the SVG's intrinsic size.
  const resvg = new Resvg(svg, { fitTo: { mode: 'original' } });
  const rendered = resvg.render();
  const rawPng = rendered.asPng();

  // Re-encode as 32-bit RGBA (no palette) with max lossless compression.
  const out = await sharp(rawPng)
    .ensureAlpha()
    .png({ compressionLevel: 9, palette: false, effort: 10 })
    .toBuffer();

  const pngName = basename(svgName, '.svg') + '.png';
  const pngPath = join(storeDir, pngName);
  writeFileSync(pngPath, out);

  const kb = (out.length / 1024).toFixed(1);
  const meta = await sharp(out).metadata();
  const flag = out.length > 32 * 1024 ? '  ⚠ >32KB' : '';
  console.log(`✓ ${pngName}  ${meta.width}x${meta.height}  ${kb} KB${flag}`);
  if (out.length > 32 * 1024) failures++;
}

process.exit(failures ? 1 : 0);
