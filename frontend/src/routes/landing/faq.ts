export interface FaqItem {
  id: string;
  q: string;
  a: string;
}

/** Real answers about the product as it works today (see docs/IMPLEMENTATION_STATUS.md). */
export const FAQ: FaqItem[] = [
  {
    id: 'free',
    q: 'Does Howlers cost anything?',
    a: 'No. Joining, posting, chatting and listening are free. Moderator funding runs in demo mode only, so no real money moves.',
  },
  {
    id: 'browse',
    q: 'Can I look around without an account?',
    a: 'Yes. Public communities, posts and podcasts are open to browse signed out. You need an account to post, vote, join or send messages.',
  },
  {
    id: 'ai',
    q: 'How does Community AI avoid making things up?',
    a: "It only searches posts and comments you're allowed to see, numbers each claim with a citation the server checks, and says so when the sources are thin.",
  },
  {
    id: 'e2ee',
    q: 'Are my messages end-to-end encrypted?',
    a: "No, and we won't pretend they are: direct messages are private to the people in the conversation, but not end-to-end encrypted. Files can be. Switch on encryption when you upload and the file is locked in your browser before it leaves.",
  },
  {
    id: 'context',
    q: 'What are Context Mode and Digest?',
    a: 'Two ways to read a long thread fast. Context Mode maps the supporting and opposing points, the evidence and the open questions. Digest sums the thread up with quotes linked back to their comments.',
  },
  {
    id: 'pulse',
    q: 'What is Pulse?',
    a: 'Polls, predictions, opinion and debate prompts inside a community. Results stay hidden until you vote, and predictions track who called it once they resolve.',
  },
  {
    id: 'appeal',
    q: 'Can I appeal a moderator’s decision?',
    a: 'Yes. You get your own page showing what was removed and why, and you can appeal from there. If the appeal succeeds, the action is reversed. Every action goes into an append-only log.',
  },
  {
    id: 'private',
    q: 'Who can see a private community?',
    a: "Only its members. That's enforced on the server, not just hidden in the interface, so private posts stay out of search and Community AI answers for everyone else.",
  },
  {
    id: 'safety',
    q: 'How do I keep someone out of my space?',
    a: 'Block them to stop their messages in both directions, or mute a person or a whole community to keep them out of your feeds and notifications. You also choose who can message you: everyone, people you follow, or nobody.',
  },
];

const norm = (s: string) =>
  s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[’']/g, '');

/** Every word of the query must appear in the question or the answer (case and accent insensitive). */
export function filterFaq(items: FaqItem[], query: string): FaqItem[] {
  const words = norm(query).split(/\s+/).filter(Boolean);
  if (!words.length) return items;
  return items.filter((it) => {
    const hay = norm(`${it.q} ${it.a}`);
    return words.every((w) => hay.includes(w));
  });
}
