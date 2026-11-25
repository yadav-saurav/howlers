import { useId, useMemo, useState, type CSSProperties } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ArrowRight, ChevronDown, Gavel, KeyRound, Search, ShieldCheck, VolumeX } from 'lucide-react';
import { withNext } from '@/app/guards';
import { FAQ, filterFaq } from './faq';
import { Moon, Stars } from './NightSky';
import { Paw, Pup, Sparkle } from '@/design-system/Pups';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

/* ---------------------------------------------------------------------------------------------------------- */

/** Generic kinds of packs, shown on the public page. Never real community names from this site. */
const PACKS = [
  'Book clubs', 'Gamers', 'Coders', 'Movie nights', 'Hikers', 'Artists', 'Music lovers', 'Study groups',
  'Founders', 'Pet people', 'Foodies', 'Night owls',
];

/** A slow, decorative marquee of the kinds of packs people start. Purely illustrative, so screen readers skip it. */
export function PackMarquee() {
  const row = (copy: number) =>
    PACKS.map((label) => (
      <li key={`${copy}-${label}`} className="mq-item">
        <Paw className="mq-paw" color={copy % 2 ? '#b8a8ff' : '#ff9fc6'} />
        <span>{label}</span>
      </li>
    ));
  return (
    <section className="marquee" data-play aria-hidden="true">
      <div className="mq-track">
        <ul className="mq-list">{row(0)}</ul>
        <ul className="mq-list">{row(1)}</ul>
      </div>
    </section>
  );
}

/* ---------------------------------------------------------------------------------------------------------- */

const SAFETY = [
  {
    icon: VolumeX,
    title: 'Blocks and mutes',
    body: 'Block someone to stop messages both ways. Mute people or whole communities to keep them out of your feeds and notifications.',
    tint: '#ff9fc6',
  },
  {
    icon: Gavel,
    title: 'Moderation with appeals',
    body: 'Automod catches spam early, reports go to a moderator queue, every action is logged, and you can appeal a removal. Win and it’s reversed.',
    tint: '#8ff0d6',
  },
  {
    icon: KeyRound,
    title: 'Passkeys and two-factor',
    body: 'Sign in with a passkey or add a second step with an authenticator app, so your account stays yours.',
    tint: '#ffe9b0',
  },
  {
    icon: ShieldCheck,
    title: 'Your data, your call',
    body: 'Choose who can message you: everyone, people you follow, or nobody. Delete your account whenever you like and your posts are anonymised.',
    tint: '#b8a8ff',
  },
];

export function SafetySection() {
  return (
    <section id="safety" aria-labelledby="safety-title" className="safety" data-play>
      <div className="wrap">
        <div className="safety-head rv">
          <p className="eyebrow">Safety</p>
          <h2 id="safety-title" className="hl-display sec-title">
            <span>Your pack.</span> <span className="glow">Your rules.</span>
          </h2>
          <p className="sec-body">Good communities need good fences. These come built in, for members and moderators alike.</p>
          <Pup pose="sleepy" fur="dusk" className="safety-pup sway" />
          <span className="zzz" aria-hidden="true"><i>z</i><i>z</i><i>z</i></span>
        </div>
        <div className="safety-grid">
          {SAFETY.map(({ icon: Icon, title, body, tint }, i) => (
            <div key={title} className="rv" style={v({ '--rd': `${i * 0.08}s` })}>
              <article className="tilt safety-card" style={v({ '--tint': tint })}>
                <span className="safety-ico" aria-hidden="true"><Icon /></span>
                <h3>{title}</h3>
                <p>{body}</p>
              </article>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

/* ---------------------------------------------------------------------------------------------------------- */

export function FaqSection() {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<string | null>(FAQ[0].id);
  const results = useMemo(() => filterFaq(FAQ, query), [query]);
  const base = useId();
  return (
    <section id="faq" aria-labelledby="faq-title" className="faq" data-play>
      <div className="wrap faq-grid">
        <div className="faq-head rv from-l">
          <p className="eyebrow">FAQ</p>
          <h2 id="faq-title" className="hl-display sec-title">
            <span>Lost in</span> <span>the woods?</span> <span className="glow">Ask away.</span>
          </h2>
          <p className="sec-body">Straight answers about how Howlers works today.</p>
        </div>
        <div className="faq-body rv from-r">
          <div className="faq-search">
            <Search aria-hidden="true" />
            <label htmlFor={`${base}-q`} className="sr-only">Search the FAQ</label>
            <input
              id={`${base}-q`}
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search the FAQ"
              autoComplete="off"
            />
          </div>
          <p className="sr-only" aria-live="polite">
            {query ? `${results.length} matching ${results.length === 1 ? 'question' : 'questions'}` : ''}
          </p>
          <div className="faq-list">
            {results.map((f) => {
              const isOpen = open === f.id;
              const btn = `${base}-${f.id}-b`;
              const panel = `${base}-${f.id}-p`;
              return (
                <div key={f.id} className={`faq-item${isOpen ? ' open' : ''}`}>
                  <h3>
                    <button
                      id={btn}
                      type="button"
                      aria-expanded={isOpen}
                      aria-controls={panel}
                      onClick={() => setOpen(isOpen ? null : f.id)}
                    >
                      <span>{f.q}</span>
                      <ChevronDown aria-hidden="true" className="faq-chev" />
                    </button>
                  </h3>
                  <div id={panel} role="region" aria-labelledby={btn} hidden={!isOpen} className="faq-a">
                    <p>{f.a}</p>
                  </div>
                </div>
              );
            })}
            {results.length === 0 && (
              <p className="faq-empty">
                No answer matches “{query}”. Try another word, or <Link to="/about">read about Howlers</Link>.
              </p>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

/* ---------------------------------------------------------------------------------------------------------- */

export function ClosingCta({ isAuthed }: { isAuthed: boolean }) {
  const { search } = useLocation();
  return (
    <section aria-labelledby="cta-title" className="closing" data-play>
      <Stars count={46} seed={21} />
      <div className="closing-moon"><Moon /></div>
      <div className="wrap closing-inner rv">
        <div className="closing-pups" aria-hidden="true">
          <div className="howl-rings big"><span /><span /><span /></div>
          <Pup pose="howl" fur="frost" flip className="cl-pup a bob" />
          <Pup pose="sit" fur="sand" className="cl-pup b hop" />
          <Pup pose="headphones" fur="mint" className="cl-pup c bob" style={v({ '--d': '-1.5s' })} />
          <Sparkle className="cl-spark twinkle-slow" />
        </div>
        <h2 id="cta-title" className="hl-display closing-title">
          <span>Your pack is online.</span> <span className="glow">Come howl.</span>
        </h2>
        <p className="sec-body center">Find your pack &amp; howl together. Joining is free and takes a minute.</p>
        <div className="cta-row center">
          {isAuthed ? (
            <Link to="/home" className="hl-btn hl-btn-white">
              Open Howlers <ArrowRight aria-hidden="true" />
            </Link>
          ) : (
            <>
              <Link to={withNext('/register', search)} className="hl-btn hl-btn-white">
                Join the pack <ArrowRight aria-hidden="true" />
              </Link>
              <Link to={withNext('/login', search)} className="hl-btn hl-btn-glow">Log in</Link>
            </>
          )}
        </div>
      </div>
    </section>
  );
}
