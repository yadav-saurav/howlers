import type { CSSProperties, ReactNode } from 'react';
import {
  Award,
  ArrowBigDown,
  ArrowBigUp,
  BadgeCheck,
  BarChart3,
  Clock,
  Link2,
  MessageSquare,
  Network,
  ScrollText,
  Search,
  Trophy,
  Users,
} from 'lucide-react';
import { CHAT_THEMES } from '@/features/messages/themes';
import { Pup } from '@/design-system/Pups';
import { ChatMock, Quote, Ticks, TypingBubble, VoiceBubble } from './ChatMock';

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

/** The post page's discussion switcher: Comments, Digest, Context Mode (same order and look as the app). */
function ThreadsMock() {
  const comments = [
    { d: 0, who: 'luna_howls', c: '#ff9fc6', text: 'Tent. Wind picks up after midnight up there.', n: 24, up: true },
    { d: 1, who: 'river.wolf', c: '#69b4ff', text: 'Hammock with an underquilt is warmer than you think.', n: 11, up: false },
    { d: 2, who: 'kai_moon', c: '#ffe9b0', text: 'Only if there are trees at the site though?', n: 6, up: false },
    { d: 0, who: 'fern_scout', c: '#8ff0d6', text: 'Whatever you pick, bring a tarp.', n: 18, up: true },
  ];
  return (
    <div className="tilt mock app">
      <div className="th-post">
        <div className="meta"><span className="meta-av">T</span> <b>c/trail_talk</b> · u/new_pup · 5h</div>
        <div className="th-title">Tent or hammock for a first night out?</div>
      </div>
      <div className="mock-tabs">
        <span className="tab t1"><MessageSquare /> Comments (42)</span>
        <span className="tab t2"><ScrollText /> Digest</span>
        <span className="tab t3"><Network /> Context Mode</span>
      </div>
      <div className="mock-stage">
        <div className="state cyc3-a">
          {comments.map((c, i) => (
            <div key={i} className="th-row" style={v({ '--indent': c.d })}>
              <span className="th-av" style={{ background: c.c }}>{c.who[0].toUpperCase()}</span>
              <div className="th-lines">
                <span className="th-who">u/{c.who} · {i + 1}h</span>
                <span className="th-text">{c.text}</span>
                <span className={`th-vote${c.up ? ' up' : ''}`}>
                  <ArrowBigUp /> {c.n} <ArrowBigDown />
                </span>
              </div>
            </div>
          ))}
        </div>
        <div className="state cyc3-b">
          <div className="dg-head"><ScrollText /> Thread digest</div>
          <div className="dg-sec">Summary</div>
          <div className="dg-p">
            Most people say a tent is the safer first night <span className="cite">1</span>; hammock fans say it sleeps
            better if there are trees <span className="cite">3</span>.
          </div>
          <div className="dg-sec">Key takeaways</div>
          {['Wind picks up after midnight', 'An underquilt keeps a hammock warm', 'Bring a tarp either way'].map((t, i) => (
            <div key={t} className="dg-li">
              <span>{t}</span>
              <span className="cite">{[1, 2, 4][i]}</span>
            </div>
          ))}
        </div>
        <div className="state cyc3-c">
          <div className="cm-claim">
            <div className="cm-k">Main claim</div>
            <div className="cm-t">Tent or hammock for a first night out?</div>
            <div className="cm-bar">
              {[
                ['#2ECC94', 12],
                ['#EF6B5C', 7],
                ['#62AAFF', 4],
                ['#A78BFA', 3],
              ].map(([c, n], i) => (
                <span key={i} className="cm-seg" style={v({ '--g': Number(n), background: String(c) })} />
              ))}
            </div>
          </div>
          {[
            ['Supporting arguments', 12, '#2ECC94'],
            ['Counterarguments', 7, '#EF6B5C'],
            ['Evidence', 4, '#62AAFF'],
            ['Questions', 3, '#A78BFA'],
          ].map(([label, n, color]) => (
            <div key={String(label)} className="stance">
              <span className="stance-dot" style={{ background: String(color) }} />
              <span className="stance-label">{label}</span>
              <span className="stance-n">{n}</span>
            </div>
          ))}
          <div className="evidence"><Link2 /> 4 links cited by participants</div>
        </div>
      </div>
    </div>
  );
}

const INBOX = [
  { name: 'Ridge crew', room: 5, last: 'fern: who has the big flask?', time: '21:06', unread: 3, c: '#8c80ff', l: 'R' },
  { name: 'kai_moon', last: 'Voice message', voice: true, time: '21:04', c: '#ff9fc6', l: 'K' },
  { name: 'river.wolf', last: 'see you saturday', time: 'Tue', c: '#69b4ff', l: 'R', fav: true },
];

/** WhatsApp-style inbox next to a themed pack room (Golden Hour), plus the twelve shared chat themes. */
function MessagesMock() {
  return (
    <div className="msg-wrap">
      <div className="tilt mock app msg-mock">
        <div className="inbox">
          <div className="ib-title">Messages</div>
          <div className="ib-search"><Search /> Search</div>
          <div className="ib-chips">
            <span className="on">All</span>
            <span>Unread <i>3</i></span>
            <span>Favourites</span>
            <span>Rooms</span>
          </div>
          {INBOX.map((c, i) => (
            <div key={c.name} className={`ib-row${i === 0 ? ' active' : ''}`}>
              <span className="dm-av sm" style={{ background: c.c }}>{c.l}</span>
              <span className="ib-main">
                <span className="ib-top">
                  <b>{c.name}</b>
                  {c.room && <span className="ib-room">Room</span>}
                  <span className={`ib-time${c.unread ? ' on' : ''}`}>{c.time}</span>
                </span>
                <span className="ib-bottom">
                  <span className={`ib-last${c.unread ? ' on' : ''}`}>{c.last}</span>
                  {c.unread && <span className="ib-badge">{c.unread}</span>}
                </span>
              </span>
            </div>
          ))}
        </div>
        <ChatMock theme="sunset" name="Ridge crew" status="5 members" avatar={{ letter: 'R', color: '#8c80ff' }} className="msg-chat">
          <div className="tb in m1">
            <span className="tb-sender">kai_moon</span>
            clear skies tonight, who&apos;s in?
          </div>
          <div className="tb out m2">
            <Quote who="kai_moon" text="clear skies tonight, who's in?" tone="out" />
            me! leaving at 10 <Ticks time="21:02" />
          </div>
          <div className="slot">
            <TypingBubble />
            <VoiceBubble tone="in" duration="0:09" played={0} sender="fern_scout" className="m3" />
          </div>
          <div className="tb out m4">bringing cocoa <Ticks time="21:05" /></div>
        </ChatMock>
        <Pup pose="wave" fur="sand" className="mock-pup side hop" />
      </div>
      <div className="swatches">
        <div className="sw-row">
          {CHAT_THEMES.map((t) => (
            <span
              key={t.id}
              className={`sw${t.id === 'sunset' ? ' on' : ''}`}
              style={{ background: `linear-gradient(135deg, ${t.dark.out[0]}, ${t.dark.out[1]} 50%, ${t.dark.out[2]})` }}
            />
          ))}
        </div>
        <div className="sw-cap"><b>Golden Hour</b> · one of {CHAT_THEMES.length} themes, shared by everyone in the chat</div>
      </div>
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
    <div className="tilt mock app pulse-mock">
      <div className="pulse-meta">
        <span className="badge-info"><BarChart3 /> Poll</span>
        <b>c/trail_talk</b>
        <span><Clock /> Closes Fri</span>
        <span><Users /> 38 votes</span>
      </div>
      <div className="pulse-q big">When&apos;s the next pack hike?</div>
      <div className="mock-stage short">
        <div className="state cyc2-a">
          {opts.map(([o], i) => (
            <div key={o} className={`vote-opt${i === 0 ? ' on' : ''}`}><span className="radio" /> {o}</div>
          ))}
          <div className="vote-row">
            <span className="vote-btn">Vote</span>
            <span className="pulse-foot">Results appear after you vote or when the Pulse closes.</span>
          </div>
        </div>
        <div className="state cyc2-b">
          {opts.map(([o, pct], i) => (
            <div key={o} className="rbar big">
              <span className="rbar-label">{o}</span>
              <span className="rbar-track">
                <span className={`rbar-fill grow2${i === 0 ? ' mine' : ''}`} style={v({ '--w': `${pct}%`, '--d': `${i * 0.2}s` })} />
              </span>
              <span className="rbar-pct">{pct}%</span>
            </div>
          ))}
          <div className="pulse-foot">Your vote: Saturday · Change my vote</div>
        </div>
      </div>
      <div className="predict"><Trophy /> Predictions keep score once they resolve</div>
    </div>
  );
}

const MEMBERS = [
  { who: 'luna_howls', c: '#ff9fc6', posts: 14, comments: 61, pts: 214 },
  { who: 'river.wolf', c: '#69b4ff', posts: 9, comments: 58, pts: 187 },
  { who: 'kai_moon', c: '#ffe9b0', posts: 11, comments: 40, pts: 152 },
  { who: 'fern_scout', c: '#8ff0d6', posts: 6, comments: 37, pts: 118 },
];

/** The Leaderboard page: Members, Moderators and Topics tabs. */
function LeaderboardMock() {
  return (
    <div className="tilt mock app lb-mock">
      <div className="lb-title">Leaderboard</div>
      <div className="seg">
        <span className="tab t1"><Users /> Members</span>
        <span className="tab t2"><Trophy /> Moderators</span>
        <span className="tab t3"><Award /> Topics</span>
      </div>
      <div className="mock-stage">
        <div className="state cyc3-a">
          <div className="lb-sub">Most active this month</div>
          <ol className="lb-list">
            {MEMBERS.map((m, i) => (
              <li key={m.who} className="lb-row">
                <span className={`lb-rank r${i + 1}`}>#{i + 1}</span>
                <span className="lb-av" style={{ background: m.c }}>{m.who[0].toUpperCase()}</span>
                <span className="lb-main">
                  <b>u/{m.who}</b>
                  <span>{m.posts} posts · {m.comments} comments</span>
                </span>
                <span className="lb-pts"><b>{m.pts}</b> pts</span>
              </li>
            ))}
          </ol>
          <div className="lb-foot">3 points a post · 1 a comment · 1 per upvote from someone else</div>
        </div>
        <div className="state cyc3-b">
          <div className="lb-sub">Moderators · quality score</div>
          <ol className="lb-list">
            {[
              ['river.wolf', 'c/trail_talk', '#69b4ff', '92.4', ['Fast responder', 'Consistent']],
              ['luna_howls', 'c/night_sky', '#ff9fc6', '88.1', ['Upheld on appeal']],
              ['moss_den', 'c/campfire', '#b8a8ff', '81.7', ['Peer endorsed']],
            ].map(([who, comm, c, score, badges], i) => (
              <li key={String(who)} className="lb-row">
                <span className="lb-rank">#{i + 1}</span>
                <span className="lb-av" style={{ background: String(c) }}>{String(who)[0].toUpperCase()}</span>
                <span className="lb-main">
                  <b>u/{who} <em>{comm}</em></b>
                  <span className="lb-badges">
                    {(badges as string[]).map((b) => <span key={b} className="mod-badge"><BadgeCheck /> {b}</span>)}
                  </span>
                </span>
                <span className="lb-score"><b>{score}</b><span>of 100</span></span>
              </li>
            ))}
          </ol>
          <div className="lb-foot">Consistency, response time, appeal outcomes, community health and peer feedback</div>
        </div>
        <div className="state cyc3-c">
          <div className="lb-topics">
            {[
              ['Astronomy', [['luna_howls', '#ff9fc6', '1.2k'], ['kai_moon', '#ffe9b0', '860']]],
              ['Hiking', [['river.wolf', '#69b4ff', '940'], ['fern_scout', '#8ff0d6', '512']]],
            ].map(([topic, rows]) => (
              <div key={String(topic)} className="lb-topic">
                <div className="lb-topic-h">{topic}</div>
                {(rows as string[][]).map(([who, c, pts], i) => (
                  <div key={who} className="lb-mini">
                    <span className="lb-rank">#{i + 1}</span>
                    <span className="lb-av" style={{ background: c }}>{who[0].toUpperCase()}</span>
                    <span className="lb-name">u/{who}</span>
                    <b>{pts}</b>
                  </div>
                ))}
              </div>
            ))}
          </div>
          <div className="lb-foot">Topic reputation comes from upvotes by other members</div>
        </div>
      </div>
      <Pup pose="sit" fur="frost" className="mock-pup peek bob" />
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
            Nested comments with upvotes, downvotes and reactions. When a thread runs long, switch to Digest for a short
            summary with every point linked to its comment, or to Context Mode to see who supports, who pushes back and
            what evidence people shared.
          </>
        }
      >
        <ThreadsMock />
      </Feature>
      <Feature
        id="messages"
        flip
        eyebrow="Messages · Pack rooms"
        lines={['Say hi.', 'Send a howl.', 'Pick a vibe.']}
        body={
          <>
            Chat one-to-one or start a pack room with your crew. Send photos and voice notes, reply to any message, and
            watch the typing dots and read ticks. Choose from twelve chat themes, and everyone in the chat sees the same
            one.
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
      <Feature
        id="leaderboard"
        flip
        eyebrow="Leaderboard"
        lines={['Show up.', 'Help out.', 'Climb up.']}
        body={
          <>
            See who kept the pack talking this month: 3 points a post, 1 a comment and 1 for every upvote from someone
            else. Moderators get a quality score for being consistent, quick and fair, and topic rankings show whose
            answers people trust.
          </>
        }
      >
        <LeaderboardMock />
      </Feature>
    </div>
  );
}
