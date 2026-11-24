import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  makeStars,
  pointerToParallax,
  revealRootMargin,
  startIntro,
  startPlayGate,
  startReveal,
  tiltFromPointer,
} from './motion';

const rect = { left: 100, top: 50, width: 400, height: 200 };

describe('pointerToParallax', () => {
  it('maps the centre to 0 and the corners to ±1', () => {
    expect(pointerToParallax(300, 150, rect)).toEqual({ mx: 0, my: 0 });
    expect(pointerToParallax(100, 50, rect)).toEqual({ mx: -1, my: -1 });
    expect(pointerToParallax(500, 250, rect)).toEqual({ mx: 1, my: 1 });
    expect(pointerToParallax(400, 100, rect)).toEqual({ mx: 0.5, my: -0.5 });
  });
  it('clamps outside the rect and survives an empty rect', () => {
    expect(pointerToParallax(-999, 9999, rect)).toEqual({ mx: -1, my: 1 });
    expect(pointerToParallax(10, 10, { left: 0, top: 0, width: 0, height: 0 })).toEqual({ mx: 0, my: 0 });
  });
});

describe('tiltFromPointer', () => {
  it('rotates Y with x, X against y, and moves the glare', () => {
    expect(tiltFromPointer(500, 250, rect)).toEqual({ ry: 4, rx: -4, gx: 100, gy: 100 });
    expect(tiltFromPointer(100, 50, rect)).toEqual({ ry: -4, rx: 4, gx: 0, gy: 0 });
    const c = tiltFromPointer(300, 150, rect);
    expect(c.gx).toBe(50);
    expect(c.gy).toBe(50);
    expect(Math.abs(c.rx)).toBe(0);
    expect(Math.abs(c.ry)).toBe(0);
  });
  it('honours a custom max angle', () => {
    expect(tiltFromPointer(500, 150, rect, 10).ry).toBe(10);
  });
});

describe('makeStars', () => {
  it('is deterministic and in range', () => {
    const a = makeStars(40, 3);
    expect(a).toEqual(makeStars(40, 3));
    expect(a).not.toEqual(makeStars(40, 4));
    for (const s of a) {
      expect(s.x).toBeGreaterThanOrEqual(0);
      expect(s.x).toBeLessThanOrEqual(100);
      expect(s.y).toBeGreaterThanOrEqual(0);
      expect(s.y).toBeLessThanOrEqual(100);
      expect(s.size).toBeGreaterThanOrEqual(1);
      expect(s.duration).toBeGreaterThanOrEqual(2.8);
    }
  });
});

describe('startIntro', () => {
  let root: HTMLElement;
  let art: HTMLElement;
  const raf = (cb: () => void) => setTimeout(cb, 16) as unknown as number;
  const cancelRaf = (id: number) => clearTimeout(id);

  beforeEach(() => {
    vi.useFakeTimers();
    root = document.createElement('div');
    art = document.createElement('div');
    root.appendChild(art);
  });
  afterEach(() => vi.useRealTimers());

  it('adds .intro now and .intro-play after fonts + 700 ms + two frames', async () => {
    let resolveFonts!: () => void;
    const fontsReady = new Promise<void>((r) => (resolveFonts = r));
    startIntro(root, art, { fontsReady, raf, cancelRaf });
    expect(root.classList.contains('intro')).toBe(true);
    expect(root.classList.contains('intro-play')).toBe(false);

    await vi.advanceTimersByTimeAsync(1000);
    expect(root.classList.contains('intro-play')).toBe(false); // fonts not ready yet

    resolveFonts();
    await vi.advanceTimersByTimeAsync(699);
    expect(root.classList.contains('intro-play')).toBe(false);
    await vi.advanceTimersByTimeAsync(1 + 16);
    expect(root.classList.contains('intro-play')).toBe(false); // one frame only
    await vi.advanceTimersByTimeAsync(16);
    expect(root.classList.contains('intro-play')).toBe(true);
  });

  it("ends on the art's own animationend, ignoring bubbled child events", async () => {
    startIntro(root, art, { fontsReady: Promise.resolve(), raf, cancelRaf });
    await vi.advanceTimersByTimeAsync(800);
    expect(root.classList.contains('intro-play')).toBe(true);

    const child = document.createElement('span');
    art.appendChild(child);
    child.dispatchEvent(new Event('animationend', { bubbles: true }));
    expect(root.classList.contains('intro')).toBe(true);

    art.dispatchEvent(new Event('animationend', { bubbles: true }));
    expect(root.classList.contains('intro')).toBe(false);
    expect(root.classList.contains('intro-play')).toBe(false);
  });

  it('falls back to the 3.2 s play safety timeout', async () => {
    startIntro(root, art, { fontsReady: Promise.resolve(), raf, cancelRaf });
    await vi.advanceTimersByTimeAsync(732);
    expect(root.classList.contains('intro-play')).toBe(true);
    await vi.advanceTimersByTimeAsync(3199);
    expect(root.classList.contains('intro')).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(root.className).toBe('');
  });

  it('falls back to the 5 s hard timeout when fonts never load', async () => {
    startIntro(root, art, { fontsReady: new Promise(() => {}), raf, cancelRaf });
    await vi.advanceTimersByTimeAsync(4999);
    expect(root.classList.contains('intro')).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(root.classList.contains('intro')).toBe(false);
  });

  it('cleans up on unmount and never plays afterwards', async () => {
    const stop = startIntro(root, art, { fontsReady: Promise.resolve(), raf, cancelRaf });
    stop();
    expect(root.className).toBe('');
    await vi.advanceTimersByTimeAsync(6000);
    expect(root.className).toBe('');
  });
});

describe('startReveal', () => {
  type Cb = (entries: { isIntersecting: boolean; target: Element }[]) => void;
  let instances: { cb: Cb; observed: Element[]; unobserved: Element[]; options?: IntersectionObserverInit; disconnected: boolean }[];

  beforeEach(() => {
    instances = [];
    class FakeIO {
      inst: (typeof instances)[number];
      constructor(cb: Cb, options?: IntersectionObserverInit) {
        this.inst = { cb, observed: [], unobserved: [], options, disconnected: false };
        instances.push(this.inst);
      }
      observe(el: Element) { this.inst.observed.push(el); }
      unobserve(el: Element) { this.inst.unobserved.push(el); }
      disconnect() { this.inst.disconnected = true; }
    }
    vi.stubGlobal('IntersectionObserver', FakeIO);
  });
  afterEach(() => vi.unstubAllGlobals());

  function build() {
    const root = document.createElement('div');
    root.innerHTML = '<div class="rv" id="a"></div><div class="rv from-l" id="b"></div><p id="c"></p>';
    return root;
  }

  it('adds .in once per element and unobserves it', () => {
    const root = build();
    startReveal(root, { mobile: false, reduced: false });
    expect(root.classList.contains('rv-on')).toBe(true);
    const io = instances[0];
    expect(io.observed.map((e) => e.id)).toEqual(['a', 'b']);
    expect(io.options?.rootMargin).toBe('0px 0px -8% 0px');
    const a = root.querySelector('#a')!;
    io.cb([{ isIntersecting: false, target: a }]);
    expect(a.classList.contains('in')).toBe(false);
    io.cb([{ isIntersecting: true, target: a }]);
    expect(a.classList.contains('in')).toBe(true);
    expect(io.unobserved).toEqual([a]);
  });

  it('uses the mobile root margin', () => {
    startReveal(build(), { mobile: true, reduced: false });
    expect(instances[0].options?.rootMargin).toBe(revealRootMargin(true));
    expect(revealRootMargin(true)).toBe('0px 0px 12% 0px');
  });

  it('shows everything immediately under reduced motion', () => {
    const root = build();
    startReveal(root, { mobile: false, reduced: true });
    expect(instances).toHaveLength(0);
    expect(root.classList.contains('rv-on')).toBe(false);
    expect(root.querySelectorAll('.rv.in')).toHaveLength(2);
  });

  it('cleanup disconnects and drops the hidden state', () => {
    const root = build();
    const stop = startReveal(root, { mobile: false, reduced: false });
    stop();
    expect(instances[0].disconnected).toBe(true);
    expect(root.classList.contains('rv-on')).toBe(false);
  });

  it('play gate marks off-screen scopes and a hidden tab', () => {
    const root = document.createElement('div');
    root.innerHTML = '<section data-play id="s"></section>';
    const stop = startPlayGate(root);
    const io = instances[0];
    const s = root.querySelector('#s')!;
    io.cb([{ isIntersecting: false, target: s }]);
    expect(s.classList.contains('off')).toBe(true);
    io.cb([{ isIntersecting: true, target: s }]);
    expect(s.classList.contains('off')).toBe(false);

    const vis = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(root.classList.contains('tab-hidden')).toBe(true);
    vis.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(root.classList.contains('tab-hidden')).toBe(false);
    stop();
    vis.mockRestore();
  });
});
