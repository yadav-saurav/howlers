# Ranking

Implemented in `backend/.../vote/ScoreMath.java`; unit-tested in `ScoreMathTest`.

## Posts: `hot_rank`
```
hot = sign(net)·log10(max(|net|, 1))      // order of magnitude of net votes
    + 1.5 · wilson(up, down)              // approval quality, 80% Wilson lower bound
    + 0.35 · log10(1 + comments)          // discussion, logarithmic so reply-farming barely helps
    + (created_epoch − 1.7e9) / 50 400    // time: every 14 h ≈ one order of magnitude of votes
```
Why this shape:
- **Log of net score**: the 10th vote matters as much as the next 90 — big communities don't drown small ones.
- **Wilson term**: a post at 22↑/2↓ beats one at 120↑/100↓ with the same net score. Divisive bait isn't rewarded.
- **Comments are a capped bonus**: 1 000 comments add < 1.1, less than one order of magnitude of votes.
- **Time is additive in log space**, so rank never needs periodic recomputation and keyset pagination is stable.

## Feeds (scope × sort)
| Tab | Scope | Sort |
|---|---|---|
| For You | joined communities ∪ followed authors ∪ public communities in chosen topics (falls back to Popular when empty) | hot |
| Following | joined communities ∪ followed authors | new |
| Latest | public communities | new |
| Popular | public communities | hot |
| Rising | public, last 24 h | `(score + 2·comments) / (age_h + 2)^1.2` |
| Top | public, window hour/day/week/month/year/all | score |

Private communities appear only to members; hidden posts never appear to the user who hid them.

## Comments
- **Best**: Wilson lower bound (ties → score → newest). Small samples can't top a thread.
- **Controversial**: `(up+down)^(minority/majority)` — needs volume *and* balance.
- **Top / New / Old**: score, id desc, id asc.

## Karma
Each vote changes the author's karma by the vote delta (−2…+2 on switches). Self-votes never count.
Topic-scoped reputation (Phase 3/4) will build on vote events with quality weighting; see ROADMAP.
