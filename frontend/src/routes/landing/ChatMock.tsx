import type { ReactNode } from 'react';
import { CheckCheck, ChevronLeft, ImagePlus, Mic, Play } from 'lucide-react';
import { getTheme, themeVars } from '@/features/messages/themes';
import type { ChatThemeId } from '@/types/messages';

/*
 * Small, decorative copies of the real Messages screen (features/messages): a themed wallpaper, rounded tail-less
 * bubbles (your bubbles in the theme's gradient, theirs frosted), quoted replies, voice notes, read ticks, typing dots
 * and the composer (photo button, text box, mic). Colours come straight from the app's chat themes (dark palette).
 */

const BARS = [0.35, 0.6, 0.9, 0.55, 0.75, 1, 0.65, 0.4, 0.8, 0.95, 0.5, 0.7, 0.45, 0.85, 0.6, 0.3, 0.55, 0.75, 0.4, 0.25];

export function VoiceBubble({
  tone,
  duration,
  played = 0.4,
  sender,
  className = '',
}: {
  tone: 'in' | 'out';
  duration: string;
  played?: number;
  sender?: string;
  className?: string;
}) {
  return (
    <div className={`tb ${tone} tb-voice ${className}`}>
      {sender && <span className="tb-sender">{sender}</span>}
      <span className="tv-play"><Play /></span>
      <span className="tv-main">
        <span className="tv-wave">
          {BARS.map((h, i) => (
            <i key={i} style={{ height: `${h * 100}%`, opacity: (i + 0.5) / BARS.length <= played ? 1 : 0.35 }} />
          ))}
        </span>
        <span className="tv-dur">{duration}</span>
      </span>
    </div>
  );
}

export function Quote({ who, text, tone }: { who: string; text: string; tone: 'in' | 'out' }) {
  return (
    <span className={`tq ${tone}`}>
      <b>{who}</b>
      <span>{text}</span>
    </span>
  );
}

export function Ticks({ time }: { time: string }) {
  return (
    <span className="tt">
      {time} <CheckCheck />
    </span>
  );
}

export function TypingBubble({ className = '' }: { className?: string }) {
  return (
    <div className={`tb in typing-b ${className}`}>
      <span /><span /><span />
    </div>
  );
}

interface ChatMockProps {
  theme: ChatThemeId;
  name: string;
  status: string;
  avatar: { letter: string; color: string };
  className?: string;
  children: ReactNode;
}

/** A themed conversation: header, wallpaper with the given bubbles, and the composer. */
export function ChatMock({ theme, name, status, avatar, className = '', children }: ChatMockProps) {
  return (
    <div className={`tchat ${className}`} style={themeVars(getTheme(theme))}>
      <div className="tc-head">
        <ChevronLeft className="tc-back" />
        <span className="dm-av sm" style={{ background: avatar.color }}>{avatar.letter}</span>
        <div className="tc-who">
          <div className="ph-name">{name}</div>
          <div className="ph-status">{status}</div>
        </div>
      </div>
      <div className="tc-wall">{children}</div>
      <div className="tc-compose">
        <span className="tc-field">
          <ImagePlus />
          <span>Message</span>
        </span>
        <span className="tc-mic"><Mic /></span>
      </div>
    </div>
  );
}
