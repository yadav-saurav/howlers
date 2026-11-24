import { describe, expect, it } from 'vitest';
import {
  accountAgeDays,
  describeAudit,
  nextCommunityStatuses,
  ruleLabel,
  signupSeries,
  sumCounts,
  userActions,
} from './helpers';

describe('signupSeries', () => {
  it('labels UTC days without shifting them and keeps every day', () => {
    const s = signupSeries([
      { day: '2026-11-09', count: 0 },
      { day: '2026-11-10', count: 3 },
    ]);
    expect(s).toEqual([
      { day: '2026-11-09', label: 'Nov 9', count: 0 },
      { day: '2026-11-10', label: 'Nov 10', count: 3 },
    ]);
    expect(sumCounts([{ day: 'a', count: 2 }, { day: 'b', count: 5 }])).toBe(7);
  });
});

describe('userActions', () => {
  const base = { id: 2, role: 'USER' as const, status: 'ACTIVE' as const };
  it('never offers actions on yourself or deleted accounts', () => {
    expect(userActions(base, 2)).toEqual([]);
    expect(userActions({ ...base, status: 'DELETED' }, 1)).toEqual([]);
  });
  it('offers suspend and promote for active users, demote but not suspend for admins', () => {
    expect(userActions(base, 1)).toEqual(['suspend', 'promote']);
    expect(userActions({ ...base, role: 'ADMIN' }, 1)).toEqual(['demote']);
  });
  it('offers only unsuspend for suspended accounts', () => {
    expect(userActions({ ...base, status: 'SUSPENDED' }, 1)).toEqual(['unsuspend']);
  });
});

describe('describeAudit', () => {
  it('names the target user or community', () => {
    const u = { id: 1, username: 'bob', displayName: null, avatarUrl: null };
    expect(describeAudit({ action: 'SUSPEND_USER', targetUser: u, targetCommunity: null })).toBe('suspended u/bob');
    expect(describeAudit({ action: 'QUARANTINE_COMMUNITY', targetUser: null, targetCommunity: { id: 3, slug: 'x', name: 'X', iconUrl: null } }))
      .toBe('quarantined c/x');
  });
});

describe('misc', () => {
  it('labels rules, including unknown ones', () => {
    expect(ruleLabel('DUPLICATE')).toBe('Same content in several communities');
    expect(ruleLabel('NEW_RULE')).toBe('new rule');
  });
  it('lists the other community statuses', () => {
    expect(nextCommunityStatuses('ACTIVE')).toEqual(['QUARANTINED', 'BANNED']);
    expect(nextCommunityStatuses('BANNED')).toEqual(['ACTIVE', 'QUARANTINED']);
  });
  it('computes account age in days', () => {
    expect(accountAgeDays('2026-11-20T00:00:00Z', '2026-11-23T12:00:00Z')).toBe(3);
    expect(accountAgeDays(null, '2026-11-23T12:00:00Z')).toBeNull();
  });
});
