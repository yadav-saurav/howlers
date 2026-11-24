import { forwardRef, type CSSProperties } from 'react';
import { ArrowBigDown, ArrowBigUp, BarChart3, Bookmark, Headphones, Home, MessageSquare, Search, Sparkles } from 'lucide-react';
import { Moon } from './NightSky';
import { Pup, Sparkle } from '@/design-system/Pups';

const v = (vars: Record<string, string | number>) => vars as CSSProperties;

/**
 * Hero art: an illustrated mock of the Howlers app (community feed card, Community AI answer, a Pulse poll),
 * a phone with a live DM, a podcast mini player, the moon and the pups. Purely decorative (aria-hidden); the
 * people, communities and numbers in it are illustrative, which the window's title bar also says.
 * Sizes are in em so the whole scene scales with the font-size set on `.hero-art` in landing.css.
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
                <span className="rail-ico"><Headphones /></span>
                <span className="rail-ico"><Bookmark /></span>
                <span className="rail-sep" />
                <span className="rail-comm" style={{ background: '#7a7dff' }}>N</span>
                <span className="rail-comm" style={{ background: '#2ecc94' }}>T</span>
                <span className="rail-comm" style={{ background: '#ff8fbd' }}>B</span>
              </div>
              <div className="win-main">
                <div className="win-head">
                  <span className="comm-ico">N</span>
                  <b>c/night_sky</b>
                  <span className="pill-joined">Joined</span>
                </div>
                <div className="win-cols">
                  <div className="win-col">
                    <div className="post-card">
                      <div className="votes">
                        <span className="vote-up"><ArrowBigUp /></span>
                        <b>128</b>
                        <span className="vote-down"><ArrowBigDown /></span>
                        <span className="plus-one f1">+1</span>
                        <span className="plus-one f2">+1</span>
                      </div>
                      <div className="post-main">
                        <div className="meta">u/luna_howls · 2h</div>
                        <div className="post-title">Where&apos;s the darkest sky within an hour of town?</div>
                        <div className="post-actions">
                          <span><MessageSquare /> 42</span>
                          <span className="chip chip-brand">Digest</span>
                          <span className="chip">Context Mode</span>
                        </div>
                      </div>
                    </div>
                    <div className="ai-card">
                      <div className="ai-head"><Sparkles /> Community AI</div>
                      <div className="ai-q">Where do people go to see the aurora?</div>
                      <div className="ai-a">
                        Most threads point to the north ridge lot <span className="cite c1">1</span>. Bring a red light and
                        arrive before 11 <span className="cite c2">2</span>.
                      </div>
                      <div className="ai-src">
                        <span>c/night_sky · post</span>
                        <span>c/hiking · comment</span>
                      </div>
                    </div>
                  </div>
                  <div className="win-col narrow">
                    <div className="pulse-card">
                      <div className="pulse-head"><BarChart3 /> Pulse · poll</div>
                      <div className="pulse-q">Meteor shower plans?</div>
                      {[
                        ['Ridge hike', 58],
                        ['Backyard', 31],
                        ['Sleeping in', 11],
                      ].map(([label, pct], i) => (
                        <div key={label} className="pbar">
                          <span className="pbar-fill" style={v({ '--w': `${pct}%`, '--d': `${i * 0.25}s` })} />
                          <span className="pbar-label">{label}</span>
                          <span className="pbar-pct">{pct}%</span>
                        </div>
                      ))}
                      <div className="pulse-foot">214 votes · closes Fri</div>
                    </div>
                    <div className="digest-card">
                      <div className="digest-head">Thread Digest</div>
                      <span className="dl" style={v({ '--w': '92%' })} />
                      <span className="dl" style={v({ '--w': '74%' })} />
                      <span className="dl" style={v({ '--w': '84%' })} />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* DM bubble floating over the window */}
        <div className="px art-dm" style={v({ '--depth': 1.4 })}>
          <div className="dm-float bob-slow">
            <span className="dm-av" style={{ background: '#ff8fbd' }}>K</span>
            <div>
              <div className="dm-name">kai_moon</div>
              <div className="dm-text">coming to the ridge tonight?</div>
            </div>
          </div>
          <div className="dm-typing">
            <span /><span /><span />
          </div>
        </div>

        {/* podcast mini player */}
        <div className="px art-pod" style={v({ '--depth': 1.2 })}>
          <div className="pod-chip">
            <span className="pod-cover"><Headphones /></span>
            <div className="pod-meta">
              <div className="pod-title">Night sounds, explained</div>
              <div className="pod-sub">c/night_sky podcast · Ep. 12</div>
              <div className="pod-track"><span className="pod-progress" /></div>
            </div>
            <span className="eq"><i /><i /><i /><i /></span>
          </div>
        </div>

        {/* phone with a live DM */}
        <div className="px art-phone" style={v({ '--depth': 0.9 })}>
          <div className="phone app">
            <div className="phone-notch" />
            <div className="phone-head">
              <span className="dm-av sm" style={{ background: '#7a7dff' }}>R</span>
              <div>
                <div className="ph-name">river.wolf</div>
                <div className="ph-status"><span className="live-dot" /> online</div>
              </div>
            </div>
            <div className="chat">
              <div className="day">Today</div>
              <div className="bubble out">is the meteor shower tonight?</div>
              <div className="bubble in m1">howl at 9?</div>
              <div className="bubble out m2">only if you bring snacks</div>
              <div className="slot">
                <div className="bubble in typing-b"><span /><span /><span /></div>
                <div className="bubble in m3">deal. see you at the ridge</div>
              </div>
              <div className="seen m4">Seen</div>
            </div>
            <div className="phone-input">Message river.wolf</div>
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
