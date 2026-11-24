import type { CSSProperties, ReactNode } from 'react';
import {
  ArrowBigUp,
  BarChart3,
  Link2,
  Quote,
} from 'lucide-react';
import { Pup } from '@/design-system/Pups';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

interface FeatureProps {
  id: string;
  eyebrow: string;
  lines: [string, string, string];
  body: ReactNode;
  flip?: boolean;
  children: ReactNode;
}

/** One feature row: big three-line uppercase heading, one sentence, and a small live mock (decorative). */
function Feature({ id, eyebrow, lines, body, flip, children }: FeatureProps) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="feat" data-play>
      <div className={`feat-grid${flip ? ' flip' : ''}`}>
        <div className={`feat-copy rv ${flip ? 'from-r' : 'from-l'}`}>
          <p className="eyebrow">{eyebrow}</p>
          <h2 id={`${id}-title`} className="hl-display feat-title">
            <span>{lines[0]}</span> <span>{lines[1]}</span> <span className="glow">{lines[2]}</span>
          </h2>
          <p className="feat-body">{body}</p>
        </div>
        <div className={`feat-art rv ${flip ? 'from-l' : 'from-r'}`} aria-hidden="true">
          {children}
        </div>
      </div>
    </section>
  );
}

function ThreadsMock() {
  return (
    <div className="tilt mock app">
      <div className="mock-tabs">
        <span className="tab t1">Thread</span>
        <span className="tab t2">Context Mode</span>
        <span className="tab t3">Digest</span>
      </div>
      <div className="mock-stage">
        <div className="state cyc3-a">
          <div className="th-title">Is a tent or a hammock better for a first night out?</div>
          {[
            { d: 0, w: '86%', who: 'luna_howls' },
            { d: 1, w: '70%', who: 'river.wolf' },
            { d: 1, w: '58%', who: 'kai_moon' },
            { d: 0, w: '78%', who: 'fern_scout' },
          ].map((c, i) => (
            <div key={i} className="th-row" style={v({ '--indent': c.d })}>
              <span className="th-av" />
              <div className="th-lines">
                <span className="th-who">{c.who}</span>
                <span className="dl" style={v({ '--w': c.w })} />
              </div>
              <span className="th-vote"><ArrowBigUp /> {24 - i * 5}</span>
            </div>
          ))}
        </div>
        <div className="state cyc3-b">
          <div className="th-title">The shape of the argument</div>
          {[
            ['Tent: weather-proof', 12, '#2ecc94'],
            ['Hammock: comfier', 7, '#7a7dff'],
            ['Open questions', 3, '#ffc857'],
          ].map(([label, n, color], i) => (
            <div key={i} className="stance">
              <span className="stance-label">{label}</span>
              <span className="stance-bar">
                <span className="stance-fill" style={v({ '--w': `${(Number(n) / 12) * 100}%`, background: String(color) })} />
              </span>
              <span className="stance-n">{n}</span>
            </div>
          ))}
          <div className="evidence"><Link2 /> 4 links cited as evidence</div>
        </div>
        <div className="state cyc3-c">
          <div className="th-title">Digest · 3 takeaways</div>
          {[
            ['fact', 'Tents win below 5 °C', '90%'],
            ['opinion', 'Hammocks sleep better', '78%'],
            ['anecdote', 'Bring a tarp either way', '66%'],
          ].map(([tag, text], i) => (
            <div key={i} className="dg-row">
              <span className={`dg-tag ${tag}`}>{tag}</span>
              <span className="dg-text">{text}</span>
              <Quote className="dg-q" />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function MessagesMock() {
  return (
    <div className="tilt mock app chat-mock">
      <div className="phone-head">
        <span className="dm-av sm" style={{ background: '#2ecc94' }}>F</span>
        <div>
          <div className="ph-name">fern_scout</div>
          <div className="ph-status"><span className="live-dot" /> online</div>
        </div>
      </div>
      <div className="chat">
        <div className="bubble in m1">saw your post about the ridge!</div>
        <div className="bubble out m2">come along on saturday?</div>
        <div className="slot">
          <div className="bubble in typing-b"><span /><span /><span /></div>
          <div className="bubble in m3">yes!! bringing cocoa</div>
        </div>
        <div className="seen m4">Seen</div>
      </div>
      <Pup pose="wave" fur="sand" className="mock-pup side hop" />
    </div>
  );
}

function PulseMock() {
  const opts: [string, number][] = [
    ['Saturday', 64],
    ['Sunday', 27],
    ['Rain check', 9],
  ];
  return (
    <div className="tilt mock app">
      <div className="pulse-head"><BarChart3 /> Pulse · poll</div>
      <div className="pulse-q big">When&apos;s the next pack hike?</div>
      <div className="mock-stage short">
        <div className="state cyc2-a">
          {opts.map(([o]) => (
            <div key={o} className="vote-opt"><span className="radio" /> {o}</div>
          ))}
          <div className="pulse-foot">Results appear after you vote</div>
        </div>
        <div className="state cyc2-b">
          {opts.map(([o, pct], i) => (
            <div key={o} className="pbar big">
              <span className="pbar-fill grow2" style={v({ '--w': `${pct}%`, '--d': `${i * 0.2}s` })} />
              <span className="pbar-label">{o}</span>
              <span className="pbar-pct">{pct}%</span>
            </div>
          ))}
          <div className="pulse-foot">Your vote: Saturday</div>
        </div>
      </div>
      <div className="predict"><span className="live-dot" /> Prediction · resolves Friday</div>
    </div>
  );
}

export function FeatureSections() {
  return (
    <div id="features" className="features">
      <Feature
        id="threads"
        eyebrow="Threads · Context Mode · Digest"
        lines={['Threads that', 'cut through', 'the noise.']}
        body={
          <>
            Nested threads ranked for quality, not volume. Too long to read? Context Mode maps who argues what, and Digest
            sums it up with every quote linked back to its comment.
          </>
        }
      >
        <ThreadsMock />
      </Feature>
      <Feature
        id="messages"
        flip
        eyebrow="Messages"
        lines={['Say hi.', 'See it land.', 'Howl back.']}
        body={
          <>
            Live direct messages with typing dots, read receipts, photos, voice notes and replies, wrapped in a chat theme
            that fits your vibe. You choose who can message you, and blocking works both ways.
          </>
        }
      >
        <MessagesMock />
      </Feature>
      <Feature
        id="pulse"
        eyebrow="Pulse"
        lines={['See where', 'the pack', 'leans.']}
        body={
          <>
            Polls, predictions and debate prompts inside your community. Results stay hidden until you vote, so everyone
            answers honestly, and predictions keep score once they resolve.
          </>
        }
      >
        <PulseMock />
      </Feature>
    </div>
  );
}
