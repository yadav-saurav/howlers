import type { CSSProperties, ReactNode } from 'react';
import {
  ArrowBigUp,
  BarChart3,
  FileText,
  Headphones,
  KeyRound,
  Link2,
  Lock,
  Pause,
  Quote,
  Sparkles,
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

function AiMock() {
  return (
    <div className="tilt mock app">
      <div className="ai-head"><Sparkles /> Community AI</div>
      <div className="q-bubble">What do people here say about night hikes?</div>
      <div className="ai-answer">
        <span className="dl" style={v({ '--w': '94%' })} />
        <span className="dl" style={v({ '--w': '80%' })} />
        <span className="ai-cites">
          <span className="cite pop-cite" style={v({ '--d': '0.4s' })}>1</span>
          <span className="cite pop-cite" style={v({ '--d': '0.8s' })}>2</span>
          <span className="cite pop-cite" style={v({ '--d': '1.2s' })}>3</span>
        </span>
        <span className="dl" style={v({ '--w': '66%' })} />
      </div>
      <div className="src-grid">
        {['c/hiking · post', 'c/night_sky · comment', 'c/hiking · comment'].map((s, i) => (
          <div key={s} className="src-card">
            <span className="src-n">{i + 1}</span>
            <span className="src-t">{s}</span>
          </div>
        ))}
      </div>
      <div className="ai-note">Sources are posts you&apos;re allowed to see.</div>
      <Pup pose="sit" fur="dusk" className="mock-pup peek bob" />
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

function PodcastMock() {
  return (
    <div className="tilt mock app">
      <div className="pod-top">
        <div className="pod-art">
          <span className="pod-moon" />
          <Pup pose="headphones" fur="mint" className="pod-pup" />
        </div>
        <div>
          <div className="pod-kicker"><Headphones /> Community podcast</div>
          <div className="pod-big">Night sounds, explained</div>
          <div className="pod-sub">From 3 threads in c/night_sky</div>
        </div>
      </div>
      <div className="wave">
        {Array.from({ length: 28 }).map((_, i) => (
          <i key={i} style={v({ '--h': `${30 + ((i * 47) % 70)}%`, '--d': `${(i % 7) * -0.17}s` })} />
        ))}
      </div>
      <div className="pod-bar"><span className="pod-progress long" /></div>
      <div className="pod-controls"><span className="pod-btn"><Pause /></span> <span>1.25×</span> <span>Chapters</span> <span>Transcript</span></div>
      <div className="chapters">
        {[
          ['00:00', 'Why owls call at night'],
          ['07:30', 'What wolves are really saying'],
          ['14:05', 'Sources from the thread'],
        ].map(([t, c], i) => (
          <div key={c} className={`chapter ch${i + 1}`}><span>{t}</span>{c}</div>
        ))}
      </div>
    </div>
  );
}

function FilesMock() {
  return (
    <div className="tilt mock app">
      <div className="file-row">
        <span className="file-ico"><FileText /></span>
        <div className="file-meta">
          <div className="file-name">trail-map.pdf</div>
          <div className="file-sub">Encrypting in your browser…</div>
          <div className="file-track"><span className="file-progress" /></div>
        </div>
        <span className="lock-badge"><Lock /></span>
      </div>
      <div className="share-link">
        <Link2 />
        <span className="share-path">/s/7Gq2xP</span>
        <span className="share-frag">#k=••••••••</span>
      </div>
      <div className="share-hint">
        <KeyRound /> The key lives after the <b>#</b>. Browsers never send it to the server.
      </div>
      <div className="share-chips">
        <span className="chip">Expires in 7 days</span>
        <span className="chip">Password</span>
        <span className="chip">3 downloads left</span>
      </div>
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
        id="ai"
        flip
        eyebrow="Community AI"
        lines={['Ask the pack.', 'It shows', 'its sources.']}
        body={
          <>
            Ask a question and get an answer built from the posts and comments you can see, with numbered citations you can
            click. When the sources are thin, it tells you.
          </>
        }
      >
        <AiMock />
      </Feature>
      <Feature
        id="messages"
        eyebrow="Messages"
        lines={['Say hi.', 'See it land.', 'Howl back.']}
        body={
          <>
            Live direct messages with typing dots, read receipts and photos. You choose who can message you, and blocking
            works both ways.
          </>
        }
      >
        <MessagesMock />
      </Feature>
      <Feature
        id="pulse"
        flip
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
      <Feature
        id="podcasts"
        eyebrow="Podcasts"
        lines={['Stories', 'from around', 'the fire.']}
        body={
          <>
            Community podcasts made from the best threads, with chapters, a searchable transcript and every source linked.
            The player keeps going while you browse.
          </>
        }
      >
        <PodcastMock />
      </Feature>
      <Feature
        id="files"
        flip
        eyebrow="Private files"
        lines={['Your files.', 'Your key.', 'Just yours.']}
        body={
          <>
            Share files with links that expire, need a password or run out. Switch on end-to-end encryption and the file is
            locked in your browser before it leaves, so our server only ever holds ciphertext.
          </>
        }
      >
        <FilesMock />
      </Feature>
    </div>
  );
}
