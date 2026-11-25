import type { AutomodRule, AutomodRuleInput } from '@/types/automod';

/**
 * One-tap safety shields for community owners. Each shield is an ordinary automod rule with a fixed name, so it shows
 * up (and can be fine-tuned) on the Automod tab, is logged like any other rule change, and never touches moderators.
 */
export interface Shield {
  id: string;
  name: string;
  title: string;
  description: string;
  rule: AutomodRuleInput;
}

/** Common abusive words and slurs in English and Hinglish. Whole words, any case; `*` matches any ending. */
export const ABUSIVE_WORDS = [
  'fuck*', 'motherfuck*', 'fck*', 'fuk*', 'shit*', 'bullshit', 'bitch*', 'bastard*', 'asshole*', 'arsehole*',
  'dickhead*', 'cunt*', 'prick*', 'twat*', 'wanker*', 'slut*', 'whore*', 'douche*', 'jackass*',
  'retard*', 'scumbag*', 'piss off', 'son of a bitch', 'go to hell', 'kill yourself', 'kys',
  'nigg*', 'fag*', 'faggot*', 'tranny', 'chink*', 'paki', 'spic', 'kike*', 'raghead*',
  'chutiya*', 'chutiye', 'chutia*', 'madarchod*', 'maderchod*', 'behenchod*', 'bhenchod*',
  'bhosdi*', 'bhosdike', 'bsdk', 'gaand*', 'gandu*', 'lund*', 'lavde*', 'lawde*', 'lodu',
  'randi*', 'harami*', 'haramkhor*', 'kutta*', 'kutti*', 'kamina*', 'kameena*', 'saala', 'saali', 'tatti',
  'jhatu*', 'chinal', 'raand',
];

export const SHIELDS: Shield[] = [
  {
    id: 'abuse',
    name: 'Shield: Abusive language',
    title: 'Abusive language filter',
    description: 'Removes posts and comments with abusive words and slurs (English and Hinglish) the moment they are posted.',
    rule: {
      name: 'Shield: Abusive language',
      kind: 'KEYWORD',
      pattern: ABUSIVE_WORDS.join('\n'),
      threshold: null,
      appliesTo: 'BOTH',
      action: 'REMOVE',
      reason: 'Removed automatically: abusive language is not allowed in this community. Please keep it respectful.',
      enabled: true,
    },
  },
  {
    id: 'links',
    name: 'Shield: Too many links',
    title: 'Link spam shield',
    description: 'Holds anything with more than 3 links for a moderator to approve.',
    rule: {
      name: 'Shield: Too many links',
      kind: 'LINK_LIMIT',
      pattern: null,
      threshold: 3,
      appliesTo: 'BOTH',
      action: 'HOLD',
      reason: 'Held for a moderator because it contains a lot of links.',
      enabled: true,
    },
  },
  {
    id: 'new',
    name: 'Shield: Brand-new accounts',
    title: 'New-account shield',
    description: 'Holds posts from accounts younger than 1 day, a common pattern for throwaway spam.',
    rule: {
      name: 'Shield: Brand-new accounts',
      kind: 'ACCOUNT_AGE',
      pattern: null,
      threshold: 1,
      appliesTo: 'POSTS',
      action: 'HOLD',
      reason: 'Held for a moderator because your account is brand new. It will appear once approved.',
      enabled: true,
    },
  },
];

export const ruleFor = (rules: AutomodRule[] | undefined, s: Shield) => rules?.find((r) => r.name === s.name);
