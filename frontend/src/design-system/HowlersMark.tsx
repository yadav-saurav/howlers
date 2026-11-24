import { useId } from 'react';

/**
 * Howlers brand: an original front-facing tribal wolf head built from sharp shards, filled with a purple → blue → cyan
 * gradient (see docs/DESIGN_SYSTEM.md). The head is drawn as a left half plus its mirror image, so it stays exactly
 * symmetric. `HowlersMark` is the mark (optionally with the wordmark); `HowlersEmblem` adds the lettering below.
 */
export const HOWLERS = {
  name: 'Howlers',
  tagline: 'Find your pack & howl together',
} as const;

/** Left half of the head on a 100×124 grid; mirrored around x = 50 for the right half. */
function Half() {
  return (
    <>
      {/* ear: thick hooked outline */}
      <path d="M31 8 L36.5 22 L43 34.5 L38.6 33.6 L34.4 24.8 L30.4 19 L27.4 36 L32.4 41.8 L21.8 44.6 Z" />
      {/* outer ruff sweeping down the cheek */}
      <path d="M21.8 46.8 L13.4 54.6 L19.6 55.2 L9.8 64.6 L18 65.4 L11.2 76.4 L20.6 72.4 L17.4 84.2 L26.2 77.6 L31.8 94 L28.6 76.2 L23.6 66.4 L26.6 57.2 L31.6 51.4 Z" />
      {/* brow shard, eye, cheek blade, muzzle side, jaw blade */}
      <path d="M27.6 46.6 L44.6 52.6 L46.4 58.4 L41.8 56.4 L33 54.6 Z" />
      <path d="M35.2 59 L44.4 60.6 L40.2 63.4 Z" />
      <path d="M30.6 60.2 L38.6 66.8 L41.2 78.6 L36.2 72.6 L29.8 68.4 Z" />
      <path d="M44.4 64.4 L47.6 66.6 L47.8 83.4 L43.6 78.4 Z" />
      <path d="M33.6 80.8 L42.8 90.8 L48.6 99.2 L42.4 97.6 L35.4 90.2 Z" />
    </>
  );
}

function Wolf({ gradientId }: { gradientId: string }) {
  return (
    <g fill={`url(#${gradientId})`}>
      <Half />
      <g transform="translate(100 0) scale(-1 1)">
        <Half />
      </g>
      {/* centre: forehead blaze, nose, chin chevron */}
      <path d="M46.6 28 L53.4 28 L51.6 56 L50 60.4 L48.4 56 Z" />
      <path d="M43.8 86 L56.2 86 L54.6 91.8 L50 94.6 L45.4 91.8 Z" />
      <path d="M38.6 104.6 L50 115 L61.4 104.6 L58.6 110.2 L50 119.4 L41.4 110.2 Z" />
    </g>
  );
}

function Gradient({ id, y1 = 10, y2 = 122 }: { id: string; y1?: number; y2?: number }) {
  return (
    <linearGradient id={id} x1="0" y1={y1} x2="0" y2={y2} gradientUnits="userSpaceOnUse">
      {/* Theme-aware colours from tokens.css (deeper in light mode); the fallbacks are the dark-theme originals. */}
      <stop offset="0" style={{ stopColor: 'var(--logo-1, #8e2fd0)' }} />
      <stop offset=".45" style={{ stopColor: 'var(--logo-2, #5a6be6)' }} />
      <stop offset="1" style={{ stopColor: 'var(--logo-3, #36dcf4)' }} />
    </linearGradient>
  );
}

/** The wolf mark; pass withName for the wordmark beside it. `size` is the mark's height in px. */
export function HowlersMark({ size = 32, withName = false }: { size?: number; withName?: boolean }) {
  const id = `hm-${useId().replace(/:/g, '')}`;
  return (
    <span className="inline-flex items-center gap-2">
      <svg width={Math.round(size * 0.81)} height={size} viewBox="0 0 100 124" aria-hidden="true" focusable="false">
        <defs><Gradient id={id} /></defs>
        <Wolf gradientId={id} />
      </svg>
      {withName ? (
        <span className="font-display text-[17px] font-extrabold uppercase tracking-tight text-text">{HOWLERS.name}</span>
      ) : (
        <span className="sr-only">{HOWLERS.name}</span>
      )}
    </span>
  );
}

/** The wolf with "HOWLERS" lettered below, for large placements (footer, splash). `size` is the width in px. */
export function HowlersEmblem({ size = 120 }: { size?: number }) {
  const id = `he-${useId().replace(/:/g, '')}`;
  return (
    <svg width={size} height={Math.round(size * 1.48)} viewBox="0 0 100 148" role="img" aria-label={`${HOWLERS.name} emblem`}>
      <defs>
        <Gradient id={id} />
        <Gradient id={`${id}-t`} y1={126} y2={146} />
      </defs>
      <Wolf gradientId={id} />
      {/* textLength pins the lettering to the drawing's width, so wide display fonts can never clip it. */}
      <text x="50" y="143" textAnchor="middle" textLength="88" lengthAdjust="spacingAndGlyphs"
            fontFamily="Unbounded, 'Arial Black', Arial, sans-serif" fontWeight="800" fontSize="14" fill={`url(#${id}-t)`}>
        HOWLERS
      </text>
    </svg>
  );
}
