# Ranking

Implemented in `backend/.../vote/ScoreMath.java`; unit-tested in `ScoreMathTest`.

## Posts: `hot_rank`
```
e   = upvotes − downvotes + comments      // engagement: an upvote and a comment each count 1
hot = sign(e)·log10(max(|e|, 1))          // order of magnitude of engagement
    + 1.5 · wilson(up, down)              // approval quality, 80% Wilson lower bound
    + (created_epoch − 1.7e9) / 50 400    // time: every 14 h ≈ one order of magnitude of engagement
```
Why this shape:
- **Upvotes and comments decide**: the posts people vote on and talk about rise. Downvotes take a point off.
- **Log of engagement**: the 10th point matters as much as the next 90 — big communities don't drown small ones.
- **Wilson term**: a post at 22↑/2↓ beats one at 120↑/100↓ with the same net score. Divisive bait isn't rewarded.
- **Time is additive in log space**, so rank never needs periodic recomputation and keyset pagination is stable.

## Feeds (scope × sort)
| Tab | Scope | Sort |
|---|---|---|
| For You | joined communities ∪ followed authors ∪ public communities in chosen topics (falls back to Popular when empty) | hot |
| Following | joined communities ∪ followed authors | new |
| Latest | public communities | new |
| Popular | public communities | hot |
| Rising | public, last 24 h | `(score + 2·comments) / (age_h + 2)^1.2` |
| Top | public, window hour/day/week/month/year/all | score + comments |

Private communities appear only to members; hidden posts never appear to the user who hid them.

## Comments
- **Best**: Wilson lower bound (ties → score → newest). Small samples can't top a thread.
- **Controversial**: `(up+down)^(minority/majority)` — needs volume *and* balance.
- **Top / New / Old**: score, id desc, id asc.

## Karma
- **+2 post karma** for each published post, **+1 comment karma** for each published comment (taken back if the
  author deletes it; held or removed content never earns it).
- On top of that, each vote changes the author's karma by the vote delta (−2…+2 on switches). Self-votes never count.
- Profile shows post and comment karma separately; their sum is the user's total.

## Live leaderboards
- **Members**: computed on request from posts, comments and upvotes this month.
- **Moderators**: recomputed within ~15 s of any removal, ban, handled report, appeal or endorsement.
- **Topics**: 2 points per published post and 1 per published comment in the topic, plus quality-weighted votes
  (see `ReputationMath`). Communities without a topic count under **General**. Authors are recomputed within ~5 s of
  posting, commenting, deleting or receiving a vote.
- The leaderboard pages refetch every 15 s while open.
