import { useId, useMemo, useState, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Award, ChevronDown, Gavel, Search, ShieldCheck, VolumeX } from 'lucide-react';
import { useCommunities } from '@/features/communities/hooks';
import { FAQ, filterFaq } from './faq';
import { Moon, Stars } from './NightSky';
import { Paw, Pup, Sparkle } from '@/design-system/Pups';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

/* ---------------------------------------------------------------------------------------------------------- */

const FALLBACK = ['Threads', 'Context Mode', 'Digest', 'Community AI', 'Messages', 'Pulse', 'Podcasts', 'Private files'];

/**
 * A slow marquee of real community names from this instance's API. If the list can't load, it shows the feature
 * names instead (decorative), never invented communities.
 */
export function PackMarquee() {
  const q = useCommunities({ sort: 'popular', page: 0, size: 12 });
  const comms = q.data?.items ?? [];
  const real = comms.length > 0;
  const items = real ? comms.map((c) => ({ key: c.slug, label: `c/${c.slug}`, to: `/c/${c.slug}` })) : FALLBACK.map((f) => ({ key: f, label: f, to: null }));
  const row = (copy: number) =>
    items.map((it) => (
      <li key={`${copy}-${it.key}`} className="mq-item">
        <Paw className="mq-paw" color={copy % 2 ? '#b8a8ff' : '#ff9fc6'} />
        {it.to && copy === 0 ? <Link to={it.to}>{it.label}</Link> : <span>{it.label}</span>}
      </li>
    ));
  return (
    <section className="marquee" data-play aria-label={real ? 'Some communities on Howlers' : undefined} aria-hidden={real ? undefined : true}>
      <div className="mq-track">
        <ul className="mq-list">{row(0)}</ul>
        <ul className="mq-list" aria-hidden="true">{row(1)}</ul>
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
    body: 'Reports go to a moderator queue, every action is logged, and you can appeal a removal. Win the appeal and it’s reversed.',
    tint: '#8ff0d6',
  },
  {
    icon: Award,
    title: 'Fair moderator recognition',
    body: 'Mods are recognised for consistency, response time and fair appeal outcomes, never for ban counts. The formula is public.',
    tint: '#ffe9b0',
  },
  {
    icon: ShieldCheck,
    title: 'Your data, your call',
    body: 'Choose who can message you, keep collections private, and delete your account whenever you like. Your posts are anonymised.',
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
        <p className="sec-body center">Find your pack &amp; howl together. It takes a minute to join, and no time at all to look around.</p>
        <div className="cta-row center">
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
      </div>
    </section>
  );
}
