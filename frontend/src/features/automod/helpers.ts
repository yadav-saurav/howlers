import type { AutomodAction, AutomodKind, AutomodRuleInput, AutomodScope } from '@/types/automod';

/* Per-community automod (D-045): labels and draft validation for the mod-tools tab. The server re-validates. */

export const KIND_INFO: Record<AutomodKind, { label: string; help: string; uses: 'pattern' | 'threshold'; thresholdLabel?: string }> = {
  KEYWORD: {
    label: 'Keywords',
    help: 'One word or phrase per line, matched as whole words in any case. Use * for any ending, e.g. cheap* matches “cheapest”.',
    uses: 'pattern',
  },
  REGEX: {
    label: 'Pattern (RE2)',
    help: 'One regular expression in RE2 syntax, matched case-insensitively. RE2 runs in linear time; backreferences and lookarounds are not supported.',
    uses: 'pattern',
  },
  DOMAIN: {
    label: 'Blocked domains',
    help: 'One domain per line. Subdomains are included: spam.example also catches ads.spam.example.',
    uses: 'pattern',
  },
  LINK_LIMIT: { label: 'Link limit', help: 'Matches posts or comments with more links than this.', uses: 'threshold', thresholdLabel: 'Most links allowed' },
  ACCOUNT_AGE: { label: 'Minimum account age', help: 'Matches authors whose account is younger than this many days.', uses: 'threshold', thresholdLabel: 'Minimum age (days)' },
  KARMA: { label: 'Minimum karma', help: 'Matches authors with less post + comment karma than this.', uses: 'threshold', thresholdLabel: 'Minimum karma' },
};

export const ACTION_INFO: Record<AutomodAction, { label: string; help: string }> = {
  HOLD: { label: 'Hold for review', help: 'Hidden until a moderator approves it in the Held tab. The author is told why.' },
  REMOVE: { label: 'Remove', help: 'Removed at once with your reason. The author is notified and can appeal.' },
  FLAG: { label: 'Flag only', help: 'Published as normal and listed under recent automod hits for you to check.' },
};

export const SCOPE_LABEL: Record<AutomodScope, string> = { BOTH: 'Posts and comments', POSTS: 'Posts only', COMMENTS: 'Comments only' };

const RANGES: Partial<Record<AutomodKind, [number, number]>> = {
  LINK_LIMIT: [0, 100],
  ACCOUNT_AGE: [1, 3650],
  KARMA: [1, 1_000_000],
};

export function emptyRule(kind: AutomodKind = 'KEYWORD'): AutomodRuleInput {
  return {
    name: '',
    kind,
    pattern: KIND_INFO[kind].uses === 'pattern' ? '' : null,
    threshold: kind === 'LINK_LIMIT' ? 3 : kind === 'ACCOUNT_AGE' ? 7 : kind === 'KARMA' ? 10 : null,
    appliesTo: 'BOTH',
    action: 'HOLD',
    reason: '',
    enabled: true,
  };
}

/** Field errors for a draft, keyed like the server's; empty when it looks valid. */
export function validateRule(r: AutomodRuleInput): Partial<Record<'name' | 'pattern' | 'threshold' | 'reason', string>> {
  const e: Partial<Record<'name' | 'pattern' | 'threshold' | 'reason', string>> = {};
  if (!r.name.trim()) e.name = 'Name the rule.';
  else if (r.name.trim().length > 80) e.name = 'Up to 80 characters.';
  if (!r.reason.trim()) e.reason = 'Write the reason authors will see.';
  else if (r.reason.trim().length > 200) e.reason = 'Up to 200 characters.';
  const info = KIND_INFO[r.kind];
  if (info.uses === 'pattern') {
    const lines = (r.pattern ?? '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    if (lines.length === 0) e.pattern = r.kind === 'DOMAIN' ? 'Add at least one domain.' : r.kind === 'REGEX' ? 'Write a pattern.' : 'Add at least one keyword.';
    else if ((r.pattern ?? '').length > 2000) e.pattern = 'Up to 2000 characters.';
    else if (r.kind === 'KEYWORD' && lines.some((l) => l.replace(/\*/g, '').trim() === '')) e.pattern = 'A keyword can’t be only wildcards.';
    else if (r.kind === 'DOMAIN') {
      const bad = lines.find((l) => !/^([a-z]+:\/\/)?(www\.)?[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?([/?#].*)?$/i.test(l));
      if (bad) e.pattern = `“${bad}” isn’t a domain.`;
    } else if (r.kind === 'REGEX' && (r.pattern ?? '').length > 500) e.pattern = 'Patterns can be up to 500 characters.';
  } else {
    const [min, max] = RANGES[r.kind] ?? [0, Number.MAX_SAFE_INTEGER];
    if (r.threshold == null || !Number.isInteger(r.threshold) || r.threshold < min || r.threshold > max) {
      e.threshold = `Use a whole number from ${min.toLocaleString()} to ${max.toLocaleString()}.`;
    }
  }
  return e;
}

/** One-line summary for the rule list: "Keywords · 3 phrases · hold · posts and comments". */
export function describeRule(r: AutomodRuleInput): string {
  const info = KIND_INFO[r.kind];
  let what: string;
  if (info.uses === 'pattern') {
    const n = (r.pattern ?? '').split(/\r?\n/).filter((l) => l.trim()).length;
    what = r.kind === 'REGEX' ? 'pattern' : `${n} ${r.kind === 'DOMAIN' ? (n === 1 ? 'domain' : 'domains') : n === 1 ? 'phrase' : 'phrases'}`;
  } else if (r.kind === 'LINK_LIMIT') what = `more than ${r.threshold} ${r.threshold === 1 ? 'link' : 'links'}`;
  else if (r.kind === 'ACCOUNT_AGE') what = `accounts under ${r.threshold} days`;
  else what = `karma under ${r.threshold}`;
  return `${info.label} · ${what} · ${ACTION_INFO[r.action].label.toLowerCase()} · ${SCOPE_LABEL[r.appliesTo].toLowerCase()}`;
}

/** Keeps only the field the rule kind uses, so switching kinds never sends a stale pattern or number. */
export function toRequest(r: AutomodRuleInput): AutomodRuleInput {
  const pattern = KIND_INFO[r.kind].uses === 'pattern';
  return { ...r, name: r.name.trim(), reason: r.reason.trim(), pattern: pattern ? r.pattern ?? '' : null, threshold: pattern ? null : r.threshold };
}
