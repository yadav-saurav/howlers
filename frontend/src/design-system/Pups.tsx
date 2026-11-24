import type { CSSProperties } from 'react';

/**
 * Howlers mascots: round, friendly wolf pups drawn as inline SVG (original art, decorative, always aria-hidden).
 * Animation hooks are class names styled in landing.css (and mascot.css for the in-app Mascot): pup-body (hop/bob),
 * pup-head (tilt), pup-eyes (blink), pup-arm (wave), pup-tail (sway).
 *
 * Fur `theme` reads the --pup-* tokens from tokens.css, so in-app mascots stay visible on white and on near-black
 * surfaces; the named furs are fixed colours for the always-dark landing page.
 */

export type PupFur = 'frost' | 'sand' | 'dusk' | 'mint' | 'theme';
export type PupPose = 'sit' | 'headphones' | 'howl' | 'wave' | 'sleepy';

const FURS: Record<PupFur, { fur: string; shade: string; light: string }> = {
  frost: { fur: '#c9d3f7', shade: '#a3afe0', light: '#ffffff' },
  sand: { fur: '#f3b97c', shade: '#d9935a', light: '#fff2df' },
  dusk: { fur: '#8f88f2', shade: '#6d66d4', light: '#ece9ff' },
  mint: { fur: '#62d3b8', shade: '#3caf95', light: '#e3fff7' },
  theme: { fur: 'var(--pup-fur, #c9d3f7)', shade: 'var(--pup-shade, #a3afe0)', light: 'var(--pup-light, #ffffff)' },
};
const BAND = 'var(--pup-band, #2b2468)';
const SHADOW = 'var(--pup-shadow, #05031a)';

const INK = '#1d1840';
const EAR = '#ff9fc6';
const CHEEK = '#ff8fbd';

interface PupProps {
  pose?: PupPose;
  fur?: PupFur;
  /** Mirror horizontally (face right). */
  flip?: boolean;
  className?: string;
  style?: CSSProperties;
}

export function Pup({ pose = 'sit', fur = 'frost', flip, className, style }: PupProps) {
  const c = FURS[fur];
  const closed = pose === 'howl' || pose === 'sleepy';
  const headTilt = pose === 'howl' ? 'rotate(-18 60 78)' : pose === 'sleepy' ? 'rotate(8 60 78)' : undefined;
  return (
    <svg viewBox="0 0 120 124" className={className} style={style} aria-hidden="true" focusable="false">
      <ellipse className="pup-shadow" cx="60" cy="118" rx="30" ry="3.6" fill={SHADOW} opacity="0.35" />
      <g transform={flip ? 'translate(120 0) scale(-1 1)' : undefined}>
      <g className="pup-body">
        {/* tail */}
        <g className="pup-tail">
          <path d="M82 96 C104 94 112 74 104 60 C101 74 92 82 78 86 Z" fill={c.shade} />
          <path d="M104 60 C108 66 108 72 106 77 C103 73 102 66 104 60 Z" fill={c.light} />
        </g>
        {/* body */}
        <ellipse cx="60" cy="92" rx="31" ry="25" fill={c.fur} />
        <ellipse cx="60" cy="98" rx="17" ry="14" fill={c.light} />
        <ellipse cx="47" cy="114" rx="9" ry="5.2" fill={c.light} />
        {pose !== 'wave' && <ellipse cx="73" cy="114" rx="9" ry="5.2" fill={c.light} />}
        {pose === 'wave' && (
          <>
            <ellipse cx="73" cy="114" rx="9" ry="5.2" fill={c.light} />
            <g className="pup-arm">
              <path d="M82 88 Q97 80 101 62" stroke={c.fur} strokeWidth="12" strokeLinecap="round" fill="none" />
              <circle cx="101" cy="60" r="7.5" fill={c.light} />
              <circle cx="98" cy="55.5" r="1.6" fill={c.shade} />
              <circle cx="102" cy="54.6" r="1.6" fill={c.shade} />
              <circle cx="105.6" cy="57.4" r="1.6" fill={c.shade} />
            </g>
          </>
        )}
        {/* head */}
        <g className="pup-head" transform={headTilt}>
          <g className="pup-head-in">
            <path d="M32 44 Q27 16 37 7 Q50 18 55 31 Z" fill={c.fur} />
            <path d="M36.5 37 Q34 20 39.5 14 Q47 22 50 30 Z" fill={EAR} />
            <path d="M88 44 Q93 16 83 7 Q70 18 65 31 Z" fill={c.fur} />
            <path d="M83.5 37 Q86 20 80.5 14 Q73 22 70 30 Z" fill={EAR} />
            <ellipse cx="60" cy="55" rx="34" ry="29.5" fill={c.fur} />
            {/* cheek fluff */}
            <path d="M27 60 Q24 70 33 74 Q30 68 34 64 Z" fill={c.fur} />
            <path d="M93 60 Q96 70 87 74 Q90 68 86 64 Z" fill={c.fur} />
            {/* brow marks and muzzle */}
            <ellipse cx="45" cy="43.5" rx="3.4" ry="2.1" fill={c.light} />
            <ellipse cx="75" cy="43.5" rx="3.4" ry="2.1" fill={c.light} />
            <path d="M60 50 C50 50 43 58 44 66 C45 75 53 80 60 80 C67 80 75 75 76 66 C77 58 70 50 60 50 Z" fill={c.light} />
            {/* eyes */}
            {closed ? (
              <g stroke={INK} strokeWidth="2.6" strokeLinecap="round" fill="none">
                {pose === 'sleepy' ? (
                  <>
                    <path d="M40 54 Q45.5 58.5 51 54" />
                    <path d="M69 54 Q74.5 58.5 80 54" />
                  </>
                ) : (
                  <>
                    <path d="M40 55 Q45.5 50 51 55" />
                    <path d="M69 55 Q74.5 50 80 55" />
                  </>
                )}
              </g>
            ) : (
              <g className="pup-eyes">
                <ellipse cx="45.5" cy="54" rx="5.6" ry="6.6" fill={INK} />
                <ellipse cx="74.5" cy="54" rx="5.6" ry="6.6" fill={INK} />
                <circle cx="47.6" cy="51.4" r="2.1" fill="#fff" />
                <circle cx="76.6" cy="51.4" r="2.1" fill="#fff" />
                <circle cx="44" cy="56.6" r="1" fill="#fff" />
                <circle cx="73" cy="56.6" r="1" fill="#fff" />
              </g>
            )}
            {/* nose and mouth */}
            <path d="M55 62 Q60 59.2 65 62 Q63 66.4 60 66.9 Q57 66.4 55 62 Z" fill={INK} />
            <circle cx="58.4" cy="61.8" r="0.9" fill="#fff" opacity="0.7" />
            {pose === 'howl' ? (
              <ellipse cx="60" cy="72.5" rx="4.2" ry="5.4" fill={INK} />
            ) : (
              <path
                d="M60 67 L60 69.4 M60 69.4 Q56.4 73 53.4 70 M60 69.4 Q63.6 73 66.6 70"
                stroke={INK}
                strokeWidth="1.7"
                strokeLinecap="round"
                fill="none"
              />
            )}
            {pose !== 'howl' && pose !== 'sleepy' && (
              <path d="M57.6 71.6 Q60 76 62.4 71.6 Z" fill="#ff7aa8" />
            )}
            <ellipse cx="36.5" cy="65" rx="5.2" ry="3.1" fill={CHEEK} opacity="0.55" />
            <ellipse cx="83.5" cy="65" rx="5.2" ry="3.1" fill={CHEEK} opacity="0.55" />
            {pose === 'headphones' && (
              <g>
                <path d="M26 52 Q25 16 60 15 Q95 16 94 52" stroke={BAND} strokeWidth="6" fill="none" strokeLinecap="round" />
                <rect x="18.5" y="45" width="14" height="21" rx="6.5" fill="#ff7fb6" />
                <rect x="21.5" y="49" width="6" height="13" rx="3" fill="#ffd0e6" />
                <rect x="87.5" y="45" width="14" height="21" rx="6.5" fill="#ff7fb6" />
                <rect x="92.5" y="49" width="6" height="13" rx="3" fill="#ffd0e6" />
              </g>
            )}
            {pose === 'sleepy' && (
              <g>
                <path d="M54 29 Q70 6 99 13 Q106 16 103 23 Q88 22 83 39 Q71 29 54 29 Z" fill="#7b6cf0" />
                <path d="M74 14 Q80 18 83 26 M88 13 Q92 18 93 22" stroke="#a89cff" strokeWidth="3" fill="none" strokeLinecap="round" />
                <path d="M50 31 Q68 23 87 38" stroke="#fff2df" strokeWidth="6.5" fill="none" strokeLinecap="round" />
                <circle cx="103" cy="20" r="6" fill="#fff2df" />
              </g>
            )}
          </g>
        </g>
      </g>
      </g>
    </svg>
  );
}

/** Four-point sparkle. */
export function Sparkle({ className, style, color = '#ffe9b0' }: { className?: string; style?: CSSProperties; color?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} style={style} aria-hidden="true" focusable="false">
      <path d="M12 0 C13 8 16 11 24 12 C16 13 13 16 12 24 C11 16 8 13 0 12 C8 11 11 8 12 0 Z" fill={color} />
    </svg>
  );
}

/** Small paw print. */
export function Paw({ className, style, color = '#ff9fc6' }: { className?: string; style?: CSSProperties; color?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} style={style} aria-hidden="true" focusable="false">
      <ellipse cx="12" cy="15.5" rx="6" ry="5" fill={color} />
      <ellipse cx="5" cy="9.5" rx="2.4" ry="3" fill={color} />
      <ellipse cx="9.6" cy="5.2" rx="2.4" ry="3" fill={color} />
      <ellipse cx="14.4" cy="5.2" rx="2.4" ry="3" fill={color} />
      <ellipse cx="19" cy="9.5" rx="2.4" ry="3" fill={color} />
    </svg>
  );
}
