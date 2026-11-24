import { useEffect, type RefObject } from 'react';

/**
 * Motion plumbing for the Howlers landing page. Everything visual lives in landing.css (keyframes, transforms and
 * opacity only); these helpers only add classes and set CSS custom properties. Nothing here runs under
 * prefers-reduced-motion, and pointer effects only run for (hover: hover) and (pointer: fine).
 */

export const REDUCED_MOTION = '(prefers-reduced-motion: reduce)';
export const FINE_POINTER = '(hover: hover) and (pointer: fine)';
export const MOBILE = '(max-width: 767px)';

function matches(query: string): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
}

export const prefersReducedMotion = () => matches(REDUCED_MOTION);

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const round = (v: number, places = 3) => Math.round(v * 10 ** places) / 10 ** places;

/** Pointer position inside a rect mapped to [-1, 1] on both axes (0 at the centre), clamped. */
export function pointerToParallax(x: number, y: number, rect: Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>) {
  if (rect.width <= 0 || rect.height <= 0) return { mx: 0, my: 0 };
  return {
    mx: round(clamp(((x - rect.left) / rect.width) * 2 - 1, -1, 1)),
    my: round(clamp(((y - rect.top) / rect.height) * 2 - 1, -1, 1)),
  };
}

/** Tilt for a card: rotateY follows x (max ±maxDeg), rotateX follows -y, glare position in percent. */
export function tiltFromPointer(
  x: number,
  y: number,
  rect: Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>,
  maxDeg = 4,
) {
  const { mx, my } = pointerToParallax(x, y, rect);
  return {
    ry: round(mx * maxDeg, 2),
    rx: round(-my * maxDeg, 2),
    gx: round(((mx + 1) / 2) * 100, 1),
    gy: round(((my + 1) / 2) * 100, 1),
  };
}

/** Deterministic star field (seeded PRNG) so the sky is the same on every render and in screenshots. */
export interface Star {
  x: number; // %
  y: number; // %
  size: number; // px
  delay: number; // s
  duration: number; // s
}
export function makeStars(count: number, seed = 7): Star[] {
  let s = seed >>> 0 || 1;
  const rnd = () => {
    // mulberry32
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return Array.from({ length: count }, () => ({
    x: round(rnd() * 100, 2),
    y: round(rnd() * 100, 2),
    size: round(1 + rnd() * 2.2, 1),
    delay: round(rnd() * 4, 2),
    duration: round(2.8 + rnd() * 2.4, 2),
  }));
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Intro sequence                                                                                               */
/* ------------------------------------------------------------------------------------------------------------ */

export interface IntroOptions {
  /** Resolves when the display font is ready. */
  fontsReady: Promise<unknown>;
  /** Wait after fonts are ready before playing (ms). */
  delay?: number;
  /** Remove the intro classes this long after play starts if the art never reports animationend (ms). */
  playSafety?: number;
  /** Remove the intro classes this long after start no matter what (ms), e.g. if fonts never resolve. */
  hardSafety?: number;
  raf?: (cb: () => void) => number;
  cancelRaf?: (id: number) => void;
}

/**
 * Adds `.intro` to the root now; once fonts are ready plus `delay`, two animation frames later adds `.intro-play`
 * (which starts the paused CSS animations). Both classes are removed when the art finishes its entrance animation,
 * or by a safety timeout. Returns a cleanup that removes everything.
 */
export function startIntro(root: HTMLElement, art: HTMLElement | null, opts: IntroOptions): () => void {
  const { delay = 700, playSafety = 3200, hardSafety = 5000 } = opts;
  const raf = opts.raf ?? ((cb: () => void) => window.requestAnimationFrame(cb));
  const cancelRaf = opts.cancelRaf ?? ((id: number) => window.cancelAnimationFrame(id));
  const timers: ReturnType<typeof setTimeout>[] = [];
  const frames: number[] = [];
  let done = false;

  const finish = () => {
    if (done) return;
    done = true;
    root.classList.remove('intro', 'intro-play');
    art?.removeEventListener('animationend', onArtEnd);
  };
  const onArtEnd = (e: Event) => {
    if (e.target === art) finish();
  };

  root.classList.add('intro');
  art?.addEventListener('animationend', onArtEnd);
  timers.push(setTimeout(finish, hardSafety));

  opts.fontsReady
    .catch(() => undefined)
    .then(() => {
      if (done) return;
      timers.push(
        setTimeout(() => {
          frames.push(
            raf(() => {
              frames.push(
                raf(() => {
                  if (done) return;
                  root.classList.add('intro-play');
                  timers.push(setTimeout(finish, playSafety));
                }),
              );
            }),
          );
        }, delay),
      );
    });

  return () => {
    timers.forEach(clearTimeout);
    frames.forEach(cancelRaf);
    finish();
  };
}

/** Display-font readiness with a ceiling, so a blocked font never holds the page hidden. */
export function displayFontReady(family = 'Unbounded', ceilingMs = 2500): Promise<unknown> {
  const fonts = typeof document !== 'undefined' ? document.fonts : undefined;
  const ceiling = new Promise((r) => setTimeout(r, ceilingMs));
  if (!fonts) return Promise.resolve();
  const load = fonts.load(`800 1em ${family}`).then(() => fonts.ready);
  return Promise.race([load, ceiling]);
}

export function useIntro(rootRef: RefObject<HTMLElement>, artRef: RefObject<HTMLElement>) {
  useEffect(() => {
    const root = rootRef.current;
    if (!root || prefersReducedMotion()) return;
    return startIntro(root, artRef.current, { fontsReady: displayFontReady() });
  }, [rootRef, artRef]);
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Scroll reveal and the play gate                                                                              */
/* ------------------------------------------------------------------------------------------------------------ */

export function revealRootMargin(mobile: boolean) {
  return mobile ? '0px 0px 12% 0px' : '0px 0px -8% 0px';
}

/**
 * Observes every `.rv` inside the root and adds `.in` once it enters the viewport, then stops observing it.
 * The hidden starting state only applies while the root has `.rv-on`, so without this hook (or with reduced
 * motion, or without IntersectionObserver) everything is simply visible.
 */
export function startReveal(root: HTMLElement, opts: { mobile: boolean; reduced: boolean }): () => void {
  const items = Array.from(root.querySelectorAll<HTMLElement>('.rv'));
  if (opts.reduced || typeof IntersectionObserver === 'undefined') {
    items.forEach((el) => el.classList.add('in'));
    return () => {};
  }
  root.classList.add('rv-on');
  const io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) {
          e.target.classList.add('in');
          io.unobserve(e.target);
        }
      }
    },
    { rootMargin: revealRootMargin(opts.mobile), threshold: 0.01 },
  );
  items.forEach((el) => io.observe(el));
  return () => {
    io.disconnect();
    root.classList.remove('rv-on');
  };
}

/**
 * Pauses CSS loops that can't be seen: every `[data-play]` scope gets `.off` while it is out of view, and the root
 * gets `.tab-hidden` while the document is hidden. landing.css pauses all animations under those classes.
 */
export function startPlayGate(root: HTMLElement): () => void {
  const onVis = () => root.classList.toggle('tab-hidden', document.visibilityState === 'hidden');
  document.addEventListener('visibilitychange', onVis);
  onVis();
  let io: IntersectionObserver | null = null;
  if (typeof IntersectionObserver !== 'undefined') {
    io = new IntersectionObserver((entries) => {
      for (const e of entries) e.target.classList.toggle('off', !e.isIntersecting);
    });
    root.querySelectorAll('[data-play]').forEach((el) => io!.observe(el));
  }
  return () => {
    document.removeEventListener('visibilitychange', onVis);
    io?.disconnect();
  };
}

export function useRevealAndPlayGate(rootRef: RefObject<HTMLElement>) {
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const stopReveal = startReveal(root, { mobile: matches(MOBILE), reduced: prefersReducedMotion() });
    const stopGate = startPlayGate(root);
    return () => {
      stopReveal();
      stopGate();
    };
  }, [rootRef]);
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Pointer parallax and tilt cards                                                                              */
/* ------------------------------------------------------------------------------------------------------------ */

/** Sets --mx/--my on the element from the pointer (rAF-throttled). Layers use them with their own --depth. */
export function startParallax(el: HTMLElement): () => void {
  let rect: DOMRect | null = null;
  let frame = 0;
  let next = { mx: 0, my: 0 };
  const apply = () => {
    frame = 0;
    el.style.setProperty('--mx', String(next.mx));
    el.style.setProperty('--my', String(next.my));
  };
  const schedule = () => {
    if (!frame) frame = requestAnimationFrame(apply);
  };
  const onEnter = () => {
    rect = el.getBoundingClientRect();
  };
  const onMove = (e: PointerEvent) => {
    if (!rect) rect = el.getBoundingClientRect();
    next = pointerToParallax(e.clientX, e.clientY, rect);
    schedule();
  };
  const onLeave = () => {
    next = { mx: 0, my: 0 };
    schedule();
  };
  const invalidate = () => {
    rect = null;
  };
  el.addEventListener('pointerenter', onEnter);
  el.addEventListener('pointermove', onMove);
  el.addEventListener('pointerleave', onLeave);
  window.addEventListener('scroll', invalidate, { passive: true });
  window.addEventListener('resize', invalidate);
  return () => {
    cancelAnimationFrame(frame);
    el.removeEventListener('pointerenter', onEnter);
    el.removeEventListener('pointermove', onMove);
    el.removeEventListener('pointerleave', onLeave);
    window.removeEventListener('scroll', invalidate);
    window.removeEventListener('resize', invalidate);
    el.style.removeProperty('--mx');
    el.style.removeProperty('--my');
  };
}

/** Delegated tilt for every `.tilt` inside the root: --rx/--ry and the glare position --gx/--gy. */
export function startTilt(root: HTMLElement): () => void {
  let active: HTMLElement | null = null;
  let rect: DOMRect | null = null;
  let frame = 0;
  let pending: ReturnType<typeof tiltFromPointer> | null = null;
  const reset = (card: HTMLElement) => {
    card.style.removeProperty('--rx');
    card.style.removeProperty('--ry');
  };
  const onMove = (e: PointerEvent) => {
    const card = (e.target as Element | null)?.closest<HTMLElement>('.tilt') ?? null;
    if (card !== active) {
      if (active) reset(active);
      active = card;
      rect = card ? card.getBoundingClientRect() : null;
    }
    if (!active || !rect) return;
    pending = tiltFromPointer(e.clientX, e.clientY, rect);
    if (!frame)
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (!active || !pending) return;
        active.style.setProperty('--rx', `${pending.rx}deg`);
        active.style.setProperty('--ry', `${pending.ry}deg`);
        active.style.setProperty('--gx', `${pending.gx}%`);
        active.style.setProperty('--gy', `${pending.gy}%`);
      });
  };
  const onLeave = () => {
    if (active) reset(active);
    active = null;
    rect = null;
  };
  root.addEventListener('pointermove', onMove);
  root.addEventListener('pointerleave', onLeave);
  window.addEventListener('scroll', onLeave, { passive: true });
  return () => {
    cancelAnimationFrame(frame);
    root.removeEventListener('pointermove', onMove);
    root.removeEventListener('pointerleave', onLeave);
    window.removeEventListener('scroll', onLeave);
  };
}

export function usePointerEffects(rootRef: RefObject<HTMLElement>, heroRef: RefObject<HTMLElement>) {
  useEffect(() => {
    const root = rootRef.current;
    const hero = heroRef.current;
    if (!root || prefersReducedMotion() || !matches(FINE_POINTER)) return;
    const stops = [startTilt(root)];
    if (hero) stops.push(startParallax(hero));
    return () => stops.forEach((s) => s());
  }, [rootRef, heroRef]);
}
