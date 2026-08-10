# Store assets

Google Play listing graphics for this app. Everything here is generated from
version-controlled SVG sources — never hand-edit the PNGs.

## Layout

| File | Purpose | Requirement |
|---|---|---|
| `icon-512.svg` / `icon-512.png` | Store icon | 512×512, 32-bit RGBA, opaque, < 32 KB |
| `feature-graphic-1024x500.svg` / `.png` | Feature graphic | 1024×500, 32-bit RGBA, opaque, < 32 KB |
| `screenshots/` (or `emulator-artifacts/shots/`) | Phone screenshots | ≥ 2, per Play spec |
| `generate-store-assets.mjs` | SVG → PNG renderer | — |
| `validate-store-assets.mjs` | Automated validator | — |

## Regenerate / validate

```bash
npm install            # once, pulls @resvg/resvg-js + sharp
npm run gen            # SVG -> PNG for every *.svg here
npm run validate       # checks dims, colour depth, opacity, size, naming
```

Both scripts also run standalone against any store dir:
`node generate-store-assets.mjs <dir>` / `node validate-store-assets.mjs <dir>`.

The SVG must paint a full-frame opaque background — that is what makes the PNG
32-bit RGBA yet fully opaque (alpha == 255 everywhere).
