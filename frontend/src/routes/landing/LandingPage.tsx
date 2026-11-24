import { useRef, type CSSProperties } from 'react';
import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuth } from '@/features/auth/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { FeatureSections } from './FeatureSections';
import { HeroArt } from './HeroArt';
import { useIntro, usePointerEffects, useRevealAndPlayGate } from './motion';
import { Aurora, Ridge, Stars } from './NightSky';
import { Pup } from '@/design-system/Pups';
import { ClosingCta, FaqSection, PackMarquee, SafetySection } from './SafetyFaq';
import './landing.css';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

/**
 * The public Howlers landing page. Always dark by design (a night-sky scene); see docs/DESIGN_SYSTEM.md
 * "Landing page". Motion: landing.css + motion.ts, all off under prefers-reduced-motion.
 */
export default function LandingPage() {
  useDocumentTitle(null);
  const { isAuthed } = useAuth();
  const rootRef = useRef<HTMLDivElement>(null);
  const heroRef = useRef<HTMLElement>(null);
  const artRef = useRef<HTMLDivElement>(null);

  useIntro(rootRef, artRef);
  useRevealAndPlayGate(rootRef);
  usePointerEffects(rootRef, heroRef);

  return (
    <div ref={rootRef} className="hl">
      <section ref={heroRef} className="hero" aria-labelledby="hero-title" data-play>
        <div className="hero-sky" aria-hidden="true">
          <Aurora />
          <div className="px" style={v({ '--depth': -0.25 })}>
            <Stars count={80} />
          </div>
        </div>
        <Ridge />
        <div className="px hero-sleeper" style={v({ '--depth': 1.1 })} aria-hidden="true">
          <Pup pose="sleepy" fur="dusk" className="pup sway" />
          <span className="zzz"><i>z</i><i>z</i><i>z</i></span>
        </div>

        <div className="hero-grid">
          <div className="hero-copy">
            <p className="hero-eyebrow in-el" style={v({ '--d': '0s' })}>
              <span className="eyebrow-moon" aria-hidden="true" />
              Find your pack &amp; howl together
            </p>
            <h1 id="hero-title" className="hl-display hero-title">
              <span className="ln"><span className="ln-i" style={v({ '--d': '0.05s' })}>Moon’s up.</span></span>{' '}
              <span className="ln"><span className="ln-i" style={v({ '--d': '0.17s' })}>Pack’s here.</span></span>{' '}
              <span className="ln"><span className="ln-i glow" style={v({ '--d': '0.29s' })}>Let’s howl.</span></span>
            </h1>
            <p className="hero-sub in-el" style={v({ '--d': '0.45s' })}>
              Howlers is home for communities that like each other: threads that make sense, an AI that cites its
              sources, live messages, Pulse polls and community podcasts.
            </p>
            <div className="cta-row in-el" style={v({ '--d': '0.58s' })}>
              {isAuthed ? (
                <Link to="/home" className="hl-btn hl-btn-white">
                  Open your feed <ArrowRight aria-hidden="true" />
                </Link>
              ) : (
                <Link to="/register" className="hl-btn hl-btn-white">
                  Join the pack <ArrowRight aria-hidden="true" />
                </Link>
              )}
              <Link to="/explore" className="hl-btn hl-btn-glow">Explore Howlers</Link>
            </div>
            <p className="hero-note in-el" style={v({ '--d': '0.7s' })}>Free to join. Browse without an account.</p>
          </div>
          <HeroArt ref={artRef} />
        </div>
      </section>

      <PackMarquee />
      <FeatureSections />
      <SafetySection />
      <FaqSection />
      <ClosingCta isAuthed={isAuthed} />
    </div>
  );
}
