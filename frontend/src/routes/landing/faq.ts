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
    a: 'No. Joining, posting and chatting are free.',
  },
  {
    id: 'account',
    q: 'Do I need an account?',
    a: 'Yes. Communities, posts and messages are for members, so sign up or log in to look inside. Joining is free, and you can add a passkey or two-factor sign-in from your settings.',
  },
  {
    id: 'messages',
    q: 'What can I do in Messages?',
    a: 'Chat one-to-one or in pack rooms (group chats), send photos and voice notes, reply to a specific message, and see typing dots and read ticks. Pick one of twelve chat themes; everyone in the chat sees the same one.',
  },
  {
    id: 'e2ee',
    q: 'Are my messages end-to-end encrypted?',
    a: "No, and we won't pretend they are: direct messages, photos and voice notes are private to the people in the conversation, but not end-to-end encrypted.",
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
    id: 'leaderboard',
    q: 'How does the leaderboard work?',
    a: 'Members are ranked by activity this month: 3 points a post, 1 a comment and 1 for every upvote from someone else. Moderators get a quality score from 0 to 100 for consistency, response time, appeal outcomes, community health and peer feedback, never for how much they remove. Topics rank people by the upvotes they earn there.',
  },
  {
    id: 'private',
    q: 'Who can see a private community?',
    a: "Only its members. That's enforced on the server, not just hidden in the interface, so private posts stay out of search for everyone else.",
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
