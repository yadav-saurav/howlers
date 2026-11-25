import EmojiPicker, { EmojiStyle, SuggestionMode, Theme } from 'emoji-picker-react';

/** The full emoji picker (lazy-loaded by EmojiButton). Native emoji, so nothing is fetched from another site. */
export default function EmojiPickerPanel({ height, onPick }: { height: number; onPick: (emoji: string) => void }) {
  const dark = document.documentElement.classList.contains('dark');
  return (
    <EmojiPicker
      onEmojiClick={(e) => onPick(e.emoji)}
      theme={dark ? Theme.DARK : Theme.LIGHT}
      emojiStyle={EmojiStyle.NATIVE}
      suggestedEmojisMode={SuggestionMode.RECENT}
      lazyLoadEmojis
      autoFocusSearch={false}
      searchPlaceholder="Search emojis"
      previewConfig={{ showPreview: false }}
      width="100%"
      height={height}
      style={{ border: 0, borderRadius: 0, ['--epr-bg-color' as string]: 'rgb(var(--surface))', ['--epr-category-label-bg-color' as string]: 'rgb(var(--surface) / 0.95)' }}
    />
  );
}
