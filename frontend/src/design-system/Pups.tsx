import type { CSSProperties } from 'react';

/**
 * Howlers mascots: round, friendly baby wolves drawn as inline SVG (original art, decorative, always aria-hidden).
 * Animation hooks are class names styled in landing.css (and mascot.css for the in-app Mascot): pup-body (hop/bob),
 * pup-head (tilt), pup-eyes (blink), pup-arm (wave), pup-tail (sway).
 *
 * Fur `theme` reads the --pup-* tokens from tokens.css, so in-app mascots stay visible on white and on near-black
 * surfaces; the named furs are fixed colours for the always-dark landing page.
 */

export type PupFur = 'frost' | 'sand' | 'dusk' | 'mint' | 'theme';
export type PupPose = 'sit' | 'headphones' | 'howl' | 'wave' | 'sleepy';

/**
 * Wolf colouring: `fur` is the coat, `shade` the darker cap and back, `dark` the tail tip and nose bridge, `light` the
 * face mask, brows, chest ruff and paws (the classic wolf pattern, kept soft and round so they read as babies).
 */
const FURS: Record<PupFur, { fur: string; shade: string; dark: string; light: string }> = {
  frost: { fur: '#c3cbe8', shade: '#939dc8', dark: '#646d9b', light: '#f6f7ff' },
  sand: { fur: '#eaae73', shade: '#c9874c', dark: '#935a31', light: '#fff3e2' },
  dusk: { fur: '#8f88ea', shade: '#6c64cb', dark: '#47409a', light: '#eeebff' },
  mint: { fur: '#70d0b7', shade: '#49ad94', dark: '#2d7d69', light: '#e6fff8' },
  theme: {
    fur: 'var(--pup-fur, #c3cbe8)',
    shade: 'var(--pup-shade, #939dc8)',
    dark: 'var(--pup-dark, #646d9b)',
    light: 'var(--pup-light, #ffffff)',
  },
};
const BAND = 'var(--pup-band, #2b2468)';
const SHADOW = 'var(--pup-shadow, #05031a)';

const INK = '#1d1840';
const EAR = '#ffc1da';
const CHEEK = '#ff8fbd';

interface PupProps {
  pose?: PupPose;
  fur?: PupFur;
  /** Mirror horizontally (face right). */
  flip?: boolean;
  className?: string;
  style?: CSSProperties;
}

/** A chunky baby-wolf paw with three toe lines. */
function BigPaw({ x, c }: { x: number; c: { light: string; shade: string } }) {
  const toes = `M${x - 3.6} 110.6 L${x - 3.6} 114 M${x} 110 L${x} 114.2 M${x + 3.6} 110.6 L${x + 3.6} 114`;
  return (
    <g>
      <ellipse cx={x} cy="113.4" rx="10.2" ry="6" fill={c.light} />
      <path d={toes} stroke={c.shade} strokeWidth="1.3" strokeLinecap="round" opacity="0.7" />
    </g>
  );
}

export function Pup({ pose = 'sit', fur = 'frost', flip, className, style }: PupProps) {
  const c = FURS[fur];
  const closed = pose === 'howl' || pose === 'sleepy';
  const headTilt = pose === 'howl' ? 'rotate(-18 60 78)' : pose === 'sleepy' ? 'rotate(8 60 78)' : undefined;
  return (
    <svg viewBox="0 0 120 124" className={className} style={style} aria-hidden="true" focusable="false">
      <ellipse className="pup-shadow" cx="60" cy="118" rx="31" ry="3.6" fill={SHADOW} opacity="0.35" />
      <g transform={flip ? 'translate(120 0) scale(-1 1)' : undefined}>
      <g className="pup-body">
        {/* bushy wolf tail with a dark tip */}
        <g className="pup-tail">
          <path
            d="M80 101 C97 103 111 92 111 74 C111 66 107 59 101 55 C103 61 103 66 100 70 L104 71 C100 77 94 81 87 83 L90 86 C86 88 82 88 78 87 Z"
            fill={c.fur}
          />
          <path d="M101 55 C107 59 111 66 111 74 C107 71 104.5 67 104 62 C103.6 59 102.6 57 101 55 Z" fill={c.dark} />
          <path d="M86 97 C95 97 102 92 105 85" stroke={c.light} strokeWidth="3" strokeLinecap="round" fill="none" opacity="0.55" />
        </g>
        {/* body with a darker back and a fluffy chest ruff */}
        <ellipse cx="60" cy="93" rx="31" ry="24.5" fill={c.fur} />
        <path d="M32 86 C36 74 46 69 60 69 C74 69 84 74 88 86 C80 80 70 78 60 78 C50 78 40 80 32 86 Z" fill={c.shade} opacity="0.55" />
        <path
          d="M43 78 L47.5 84.5 L51.5 79 L55.5 87 L60 80 L64.5 87 L68.5 79 L72.5 84.5 L77 78 C77.5 96 71 108.5 60 108.5 C49 108.5 42.5 96 43 78 Z"
          fill={c.light}
        />
        <BigPaw x={46} c={c} />
        <BigPaw x={74} c={c} />
        {pose === 'wave' && (
          <g className="pup-arm">
            <path d="M82 88 Q97 80 101 62" stroke={c.fur} strokeWidth="12.5" strokeLinecap="round" fill="none" />
            <circle cx="101" cy="60" r="8" fill={c.light} />
            <circle cx="97.8" cy="55.2" r="1.7" fill={c.shade} />
            <circle cx="102" cy="54.2" r="1.7" fill={c.shade} />
            <circle cx="105.8" cy="57.2" r="1.7" fill={c.shade} />
          </g>
        )}
        {/* head */}
        <g className="pup-head" transform={headTilt}>
          <g className="pup-head-in">
            {/* tall, furry wolf ears */}
            <path d="M29 47 Q22 13 34 2 Q51 15 56 31 Z" fill={c.fur} />
            <path d="M34 39 Q31 18 37 10 Q47 20 50 30 Z" fill={c.light} />
            <path d="M36.5 34 Q35 22 38.5 17 Q43.5 23 45.5 29 Z" fill={EAR} />
            <path d="M91 47 Q98 13 86 2 Q69 15 64 31 Z" fill={c.fur} />
            <path d="M86 39 Q89 18 83 10 Q73 20 70 30 Z" fill={c.light} />
            <path d="M83.5 34 Q85 22 81.5 17 Q76.5 23 74.5 29 Z" fill={EAR} />
            {/* round head with fluffy cheek ruffs */}
            <path
              d="M60 24 C80 24 94 36 95 51 L102 56.5 L95 60 L100.5 67.5 L91 68.5 C87 79 75 84.5 60 84.5 C45 84.5 33 79 29 68.5 L19.5 67.5 L25 60 L18 56.5 L25 51 C26 36 40 24 60 24 Z"
              fill={c.fur}
            />
            {/* darker cap with a widow's peak between the eyes */}
            <path
              d="M60 24.6 C74 24.6 86 31 91 42 C83 39.5 73 40.5 66.5 45.5 L60 53.5 L53.5 45.5 C47 40.5 37 39.5 29 42 C34 31 46 24.6 60 24.6 Z"
              fill={c.shade}
            />
            {/* light wolf mask around the eyes and cheeks */}
            <path
              d="M60 54 L53.5 46.6 C45.5 43.6 36 45.6 31 54 C30 63.5 35.5 71.5 44 75.5 L60 80 L76 75.5 C84.5 71.5 90 63.5 89 54 C84 45.6 74.5 43.6 66.5 46.6 Z"
              fill={c.light}
              opacity="0.6"
            />
            {/* brow spots */}
            <ellipse cx="44.5" cy="42.4" rx="3.6" ry="2.2" fill={c.light} />
            <ellipse cx="75.5" cy="42.4" rx="3.6" ry="2.2" fill={c.light} />
            {/* short puppy muzzle with a darker bridge */}
            <path d="M57.4 51 Q60 49.4 62.6 51 L61.8 59 Q60 60 58.2 59 Z" fill={c.dark} opacity="0.35" />
            <path
              d="M60 56.5 C51.5 56.5 46.5 62.5 46.5 68.5 C46.5 76.5 53 81.5 60 81.5 C67 81.5 73.5 76.5 73.5 68.5 C73.5 62.5 68.5 56.5 60 56.5 Z"
              fill={c.light}
            />
            {/* eyes */}
            {closed ? (
              <g stroke={INK} strokeWidth="2.6" strokeLinecap="round" fill="none">
                {pose === 'sleepy' ? (
                  <>
                    <path d="M39.5 54 Q45 58.5 50.5 54" />
                    <path d="M69.5 54 Q75 58.5 80.5 54" />
                  </>
                ) : (
                  <>
                    <path d="M39.5 55 Q45 50 50.5 55" />
                    <path d="M69.5 55 Q75 50 80.5 55" />
                  </>
                )}
              </g>
            ) : (
              <g className="pup-eyes">
                <ellipse cx="45" cy="54" rx="5.8" ry="6.8" fill={INK} />
                <ellipse cx="75" cy="54" rx="5.8" ry="6.8" fill={INK} />
                <circle cx="47.2" cy="51.3" r="2.2" fill="#fff" />
                <circle cx="77.2" cy="51.3" r="2.2" fill="#fff" />
                <circle cx="43.4" cy="56.8" r="1" fill="#fff" />
                <circle cx="73.4" cy="56.8" r="1" fill="#fff" />
              </g>
            )}
            {/* big wolf nose and mouth */}
            <path d="M53.6 61.4 Q60 57.6 66.4 61.4 Q64.4 67 60 67.6 Q55.6 67 53.6 61.4 Z" fill={INK} />
            <ellipse cx="57.8" cy="61.3" rx="1.6" ry="0.9" fill="#fff" opacity="0.6" />
            {pose === 'howl' ? (
              <ellipse cx="60" cy="73.5" rx="4.4" ry="5.6" fill={INK} />
            ) : (
              <path
                d="M60 67.6 L60 70.2 M60 70.2 Q56.2 74 53 70.8 M60 70.2 Q63.8 74 67 70.8"
                stroke={INK}
                strokeWidth="1.7"
                strokeLinecap="round"
                fill="none"
              />
            )}
            {pose !== 'howl' && pose !== 'sleepy' && <path d="M57.5 72.4 Q60 77.2 62.5 72.4 Z" fill="#ff7aa8" />}
            <ellipse cx="35.5" cy="65.5" rx="5.2" ry="3.1" fill={CHEEK} opacity="0.55" />
            <ellipse cx="84.5" cy="65.5" rx="5.2" ry="3.1" fill={CHEEK} opacity="0.55" />
            {pose === 'headphones' && (
              <g>
                <path d="M26 52 Q25 16 60 15 Q95 16 94 52" stroke={BAND} strokeWidth="6" fill="none" strokeLinecap="round" />
                <rect x="17.5" y="45" width="14" height="21" rx="6.5" fill="#ff7fb6" />
                <rect x="20.5" y="49" width="6" height="13" rx="3" fill="#ffd0e6" />
                <rect x="88.5" y="45" width="14" height="21" rx="6.5" fill="#ff7fb6" />
                <rect x="93.5" y="49" width="6" height="13" rx="3" fill="#ffd0e6" />
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
