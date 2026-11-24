# Howlers brand assets

Repository and sharing artwork for Howlers ("Find your pack & howl together"). All artwork is original: the tribal
wolf mark comes from `frontend/src/design-system/HowlersMark.tsx` and the night sky from the landing page
(`frontend/src/routes/landing/`). The PWA icons and the in-app Open Graph image live in `frontend/public/` and are
maintained separately; this folder holds only the GitHub and README assets.

| File | What it is |
|---|---|
| `social-preview.png` | GitHub social preview, 1280×640 PNG (under 1 MB): night sky, wolf mark, "Howlers" in Unbounded, tagline |
| `logo-512.png` | 512×512 logo tile (wolf mark on the night sky), used at the top of the root README |
| `wolf-mark.svg` | The wolf mark as a standalone SVG with the dark-background gradient (transparent background) |
| `social-preview.html`, `logo.html`, `brand.css` | HTML/SVG sources the PNGs are rendered from |
| `render.sh` | Renders both PNGs with headless Chrome |
| `screenshots/` | README screenshots: `landing.png`, `home-feed.png`, `post-discussion.png` (demo data, dark theme) |

## Regenerating
```bash
cd frontend && npm ci && cd ..      # the pages load Unbounded and Inter from frontend/node_modules/@fontsource*
sh docs/brand/render.sh             # writes social-preview.png and logo-512.png
```
`render.sh` runs `chrome --headless=new --screenshot --window-size=W,H` with a throwaway `--user-data-dir` inside this
folder and deletes it afterwards. Set `CHROME=/path/to/chrome` if Chrome isn't at the default Windows location.

If the mark changes in `HowlersMark.tsx`, update the paths in `wolf-mark.svg` (it is the same left half drawn twice,
mirrored around x = 50, plus three centre shapes) and re-render.

The screenshots were taken with Playwright at 1280×760 (landing) and 1280×800 (app, signed in as the demo user
`mira_k`, dark theme, reduced motion) against a local Vite dev server proxying to the backend with the demo seed.
Retake them the same way after visible UI changes.

## Uploading the social preview (manual)
GitHub doesn't read the social preview from the repository, so the owner has to upload it once by hand:
**GitHub → the repository → Settings → General → Social preview → Edit → Upload an image**, and choose
`docs/brand/social-preview.png`. GitHub recommends 1280×640 and accepts files under 1 MB.
