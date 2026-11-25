import { forwardRef, type CSSProperties } from 'react';
import {
  ArrowBigDown,
  ArrowBigUp,
  BarChart3,
  Bookmark,
  Home,
  MessageSquare,
  Search,
  Trophy,
} from 'lucide-react';
import { Moon } from './NightSky';
import { ChatMock, Quote, Ticks, TypingBubble, VoiceBubble } from './ChatMock';
import { Pup, Sparkle } from '@/design-system/Pups';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

/**
 * Hero art: an illustrated copy of the Howlers app (a community feed post with its vote pill and reactions, its top
 * comments, a Pulse poll and this month's leaderboard), a phone with a themed chat (quoted reply, voice note, composer),
 * the moon and the pups. Purely decorative (aria-hidden); the people, communities and numbers in it are illustrative,
 * which the window's title bar also says. Sizes are in em so the scene scales with the font-size on `.hero-art`.
 */
export const HeroArt = forwardRef<HTMLDivElement>(function HeroArt(_, ref) {
  return (
    <div ref={ref} className="hero-art" aria-hidden="true">
      <div className="art-stage">
        {/* moon + howl rings */}
        <div className="px art-moon" style={v({ '--depth': -0.6 })}>
          <Moon />
        </div>

        <div className="px art-spark s1" style={v({ '--depth': 1.6 })}>
          <Sparkle className="twinkle-slow" />
        </div>
        <div className="px art-spark s2" style={v({ '--depth': 2.2 })}>
          <Sparkle className="twinkle-slow" color="#b8a8ff" style={v({ '--d': '1.2s' })} />
        </div>
        <div className="px art-spark s3" style={v({ '--depth': 1.2 })}>
          <Sparkle className="twinkle-slow" color="#8ff0d6" style={v({ '--d': '2.1s' })} />
        </div>
        <div className="px art-orb o1" style={v({ '--depth': 1.8 })}>
          <span className="orb orb-violet" />
        </div>
        <div className="px art-orb o2" style={v({ '--depth': 2.6 })}>
          <span className="orb orb-mint" />
        </div>

        {/* app window */}
        <div className="px art-window" style={v({ '--depth': 0.5 })}>
          <div className="win app">
            <div className="win-bar">
              <span className="dot" style={{ background: '#ff6b6b' }} />
              <span className="dot" style={{ background: '#ffc857' }} />
              <span className="dot" style={{ background: '#4fd1a1' }} />
              <span className="win-title">howlers · c/night_sky</span>
              <span className="win-note">illustration</span>
            </div>
            <div className="win-body">
              <div className="win-rail">
                <span className="rail-ico active"><Home /></span>
                <span className="rail-ico"><Search /></span>
                <span className="rail-ico"><MessageSquare /></span>
                <span className="rail-ico"><Trophy /></span>
                <span className="rail-ico"><Bookmark /></span>
                <span className="rail-sep" />
                <span className="rail-comm" style={{ background: '#8c80ff' }}>N</span>
                <span className="rail-comm" style={{ background: '#69b4ff' }}>T</span>
                <span className="rail-comm" style={{ background: '#ff9fc6' }}>B</span>
              </div>
              <div className="win-main">
                <div className="win-head">
                  <span className="comm-ico">N</span>
                  <b>c/night_sky</b>
                  <span className="seg-mini">
                    <span className="on">Posts</span>
                    <span>Pulse</span>
                  </span>
                  <span className="pill-joined">Joined</span>
                </div>
                <div className="win-cols">
                  <div className="win-col">
                    <div className="post-card">
                      <div className="meta">
                        <span className="meta-av">N</span> <b>c/night_sky</b> · u/luna_howls · 2h
                      </div>
                      <div className="post-title">Where&apos;s the darkest sky within an hour of town?</div>
                      <div className="post-actions">
                        <span className="vpill on">
                          <ArrowBigUp className="filled" />
                          <b>128</b>
                          <ArrowBigDown />
                          <span className="plus-one f1">+1</span>
                          <span className="plus-one f2">+1</span>
                        </span>
                        <span className="apill"><MessageSquare /> 42</span>
                        <span className="rx mine"><i>🐺</i> 9</span>
                        <span className="rx"><i>🔥</i> 4</span>
                      </div>
                    </div>
                    <div className="digest-card">
                      <div className="dg-head"><MessageSquare /> Top comments</div>
                      <div className="dg-sec">u/luna_howls · 2h · 31 upvotes</div>
                      <div className="dg-p">
                        North ridge lot, no question. Bring a red light and get there before 11.
                      </div>
                      <div className="dg-sec">u/river.wolf · 1h · 12 upvotes</div>
                      <div className="dg-p muted">Is the gate open after dark?</div>
                    </div>
                  </div>
                  <div className="win-col narrow">
                    <div className="pulse-card">
                      <div className="pulse-meta">
                        <span className="badge-info"><BarChart3 /> Poll</span>
                        <span>214 votes · Fri</span>
                      </div>
                      <div className="pulse-q">Meteor shower plans?</div>
                      {[
                        ['Ridge hike', 58, true],
                        ['Backyard', 31, false],
                        ['Staying in', 11, false],
                      ].map(([label, pct, mine], i) => (
                        <div key={String(label)} className="rbar">
                          <span className="rbar-label">{label}</span>
                          <span className="rbar-track">
                            <span
                              className={`rbar-fill${mine ? ' mine' : ''}`}
                              style={v({ '--w': `${pct}%`, '--d': `${i * 0.25}s` })}
                            />
                          </span>
                          <span className="rbar-pct">{pct}%</span>
                        </div>
                      ))}
                    </div>
                    <div className="lb-card">
                      <div className="lb-head"><Trophy /> Top this month</div>
                      {[
                        ['1', 'luna_howls', '#ff9fc6', 214],
                        ['2', 'river.wolf', '#69b4ff', 187],
                        ['3', 'kai_moon', '#ffe9b0', 152],
                      ].map(([rank, who, color, pts]) => (
                        <div key={String(who)} className="lb-mini">
                          <span className="lb-rank">#{rank}</span>
                          <span className="lb-av" style={{ background: String(color) }}>{String(who)[0].toUpperCase()}</span>
                          <span className="lb-name">{who}</span>
                          <b>{pts}</b>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* inbox preview floating over the window */}
        <div className="px art-dm" style={v({ '--depth': 1.4 })}>
          <div className="dm-float bob-slow">
            <span className="dm-av" style={{ background: '#ff9fc6' }}>K</span>
            <div className="dm-body">
              <div className="dm-name">kai_moon <span className="dm-time">now</span></div>
              <div className="dm-text">
                <span className="dm-typing-txt">typing…</span>
              </div>
            </div>
            <span className="dm-badge">2</span>
          </div>
        </div>

        {/* phone with a themed chat */}
        <div className="px art-phone" style={v({ '--depth': 0.9 })}>
          <div className="phone">
            <div className="phone-notch" />
            <ChatMock theme="sunset" name="kai_moon" status="online" avatar={{ letter: 'K', color: '#ff9fc6' }}>
              <div className="tc-day">Today</div>
              <div className="tb in m1">is the meteor shower tonight?</div>
              <div className="tb out m2">
                <Quote who="kai_moon" text="is the meteor shower tonight?" tone="out" />
                yes! ridge at 11 <Ticks time="21:02" />
              </div>
              <div className="slot">
                <TypingBubble />
                <VoiceBubble tone="in" duration="0:12" className="m3" />
              </div>
              <div className="tb out m4">bringing cocoa <Ticks time="21:04" /></div>
            </ChatMock>
          </div>
        </div>

        {/* pups */}
        <div className="px art-pup howler" style={v({ '--depth': 1.3 })}>
          <div className="howl-rings">
            <span /><span /><span />
          </div>
          <Pup pose="howl" fur="frost" flip className="pup bob" />
        </div>
        <div className="px art-pup dj" style={v({ '--depth': 1.9 })}>
          <Pup pose="headphones" fur="mint" className="pup hop" />
        </div>
        <div className="px art-pup waver" style={v({ '--depth': 1.6 })}>
          <Pup pose="wave" fur="sand" className="pup bob" style={v({ '--d': '-2s' })} />
        </div>
      </div>
    </div>
  );
});
