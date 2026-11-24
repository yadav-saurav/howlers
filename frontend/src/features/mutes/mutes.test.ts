import { describe, expect, it } from 'vitest';
import { isCommunityMuted, isUserMuted } from './api';

describe('mute lookups', () => {
  const mutes = {
    users: [{ id: 1, username: 'Loud_Larry', displayName: null, avatarUrl: null }],
    communities: [{ id: 2, slug: 'memes', name: 'Memes', iconUrl: null }],
  };
  it('matches usernames and slugs case-insensitively', () => {
    expect(isUserMuted(mutes, 'loud_larry')).toBe(true);
    expect(isUserMuted(mutes, 'quiet_quinn')).toBe(false);
    expect(isCommunityMuted(mutes, 'MEMES')).toBe(true);
    expect(isCommunityMuted(mutes, 'books')).toBe(false);
  });
  it('treats a list that has not loaded yet as not muted', () => {
    expect(isUserMuted(undefined, 'loud_larry')).toBe(false);
    expect(isCommunityMuted(undefined, 'memes')).toBe(false);
  });
});
