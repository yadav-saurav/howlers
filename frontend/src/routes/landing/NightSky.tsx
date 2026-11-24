import { useId, useMemo, type CSSProperties } from 'react';
import { makeStars } from './motion';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

/** Twinkling star field. Deterministic positions; each star has its own delay and duration. */
export function Stars({ count = 70, seed = 7, className = '' }: { count?: number; seed?: number; className?: string }) {
  const stars = useMemo(() => makeStars(count, seed), [count, seed]);
  return (
    <div className={`stars ${className}`} aria-hidden="true">
      {stars.map((s, i) => (
        <span
          key={i}
          className="star"
          style={v({
            left: `${s.x}%`,
            top: `${s.y}%`,
            width: `${s.size}px`,
            height: `${s.size}px`,
            '--d': `${s.delay}s`,
            '--t': `${s.duration}s`,
          })}
        />
      ))}
    </div>
  );
}

/** Aurora ribbons: blurred gradient bands that drift slowly (transform + opacity only). */
export function Aurora() {
  return (
    <div className="aurora" aria-hidden="true">
      <span className="ribbon r1" />
      <span className="ribbon r2" />
      <span className="ribbon r3" />
    </div>
  );
}

/** The big glowing moon with soft craters and a halo. */
export function Moon({ className = '', style }: { className?: string; style?: CSSProperties }) {
  const gid = `hl-moon-${useId().replace(/:/g, '')}`;
  return (
    <div className={`moon ${className}`} style={style} aria-hidden="true">
      <span className="moon-halo" />
      <svg viewBox="0 0 200 200" className="moon-disc" focusable="false">
        <defs>
          <radialGradient id={gid} cx="38%" cy="34%" r="70%">
            <stop offset="0" stopColor="#fffaf0" />
            <stop offset="0.55" stopColor="#ffeec4" />
            <stop offset="1" stopColor="#f6cf8c" />
          </radialGradient>
        </defs>
        <circle cx="100" cy="100" r="100" fill={`url(#${gid})`} />
        <g fill="#e9bf7c" opacity="0.38">
          <circle cx="62" cy="70" r="16" />
          <circle cx="128" cy="52" r="9" />
          <circle cx="140" cy="118" r="21" />
          <circle cx="84" cy="138" r="11" />
          <circle cx="104" cy="96" r="6" />
          <circle cx="44" cy="118" r="7" />
        </g>
      </svg>
    </div>
  );
}

/** Pine silhouette: three stacked tiers. */
function pine(x: number, base: number, h: number) {
  const w = h * 0.46;
  const t = (y0: number, y1: number, ww: number) => `M${x} ${y0} L${x + ww / 2} ${y1} L${x - ww / 2} ${y1} Z`;
  return [
    t(base - h, base - h * 0.45, w * 0.62),
    t(base - h * 0.72, base - h * 0.18, w * 0.82),
    t(base - h * 0.45, base, w),
    `M${x - 1.6} ${base} h3.2 v10 h-3.2 Z`,
  ].join(' ');
}

function forestPath() {
  let d = '';
  let s = 11;
  const rnd = () => {
    s = (s * 9301 + 49297) % 233280;
    return s / 233280;
  };
  for (let x = -10; x < 1460; x += 22 + rnd() * 26) {
    // the base follows the front hill: y ≈ 286 ± a gentle wave
    const base = 284 + Math.sin(x / 210) * 10 + 8;
    const h = 34 + rnd() * 46;
    // leave clearings so the sleepy pup and the CTA side breathe
    if (x > 120 && x < 260) continue;
    d += pine(Math.round(x), Math.round(base), Math.round(h)) + ' ';
  }
  return d;
}

/** Layered mountains and a pine forest across the bottom of the hero. Each layer is a parallax plane. */
export function Ridge() {
  const uid = useId().replace(/:/g, '');
  const forest = useMemo(forestPath, []);
  return (
    <div className="ridge" aria-hidden="true">
      <div className="px ridge-layer" style={v({ '--depth': 0.35 })}>
        <svg viewBox="0 0 1440 320" preserveAspectRatio="none" focusable="false">
          <defs>
            <linearGradient id={`hl-far-${uid}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#3b2e8f" />
              <stop offset="1" stopColor="#1d1656" />
            </linearGradient>
          </defs>
          <path
            d="M0 214 L96 162 L170 196 L300 104 L402 178 L520 128 L640 196 L770 118 L880 170 L1010 96 L1130 172 L1250 126 L1360 176 L1440 150 L1440 320 L0 320 Z"
            fill={`url(#hl-far-${uid})`}
          />
          <path d="M300 104 L326 122 L312 120 L296 132 L284 116 Z M1010 96 L1036 116 L1020 112 L1004 126 L994 108 Z M770 118 L792 134 L778 132 L764 142 L756 130 Z" fill="#8d82e6" opacity="0.55" />
        </svg>
      </div>
      <div className="px ridge-layer" style={v({ '--depth': 0.7 })}>
        <svg viewBox="0 0 1440 320" preserveAspectRatio="none" focusable="false">
          <path d="M0 246 Q180 188 360 232 T720 222 T1080 238 T1440 206 L1440 320 L0 320 Z" fill="#191347" />
        </svg>
      </div>
      <div className="px ridge-layer ridge-front" style={v({ '--depth': 1.1 })}>
        <svg viewBox="0 0 1440 320" preserveAspectRatio="xMidYMax slice" focusable="false">
          <path d="M0 290 Q240 262 480 282 T960 278 T1440 268 L1440 320 L0 320 Z" fill="#0d0b26" />
          <path d={forest} fill="#0d0b26" />
        </svg>
      </div>
      <div className="fireflies">
        {[12, 27, 44, 61, 78, 90].map((x, i) => (
          <span key={x} className="firefly" style={v({ left: `${x}%`, bottom: `${18 + ((i * 37) % 40)}px`, '--d': `${i * 0.9}s` })} />
        ))}
      </div>
    </div>
  );
}
