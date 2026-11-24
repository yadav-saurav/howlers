package app.howlers.insight;

import app.howlers.insight.InsightDtos.DigestSections;
import app.howlers.insight.ThreadLoader.Snapshot;

/**
 * Pluggable digest generator. The built-in {@link ExtractiveDigestEngine} works offline by selecting and
 * attributing real sentences; an LLM-backed engine can implement this interface (configured by environment,
 * keys server-side only) as long as every line it returns cites a comment id from the snapshot.
 */
public interface DigestEngine {
    String id();

    /** Human-readable note shown with the digest, e.g. how it was produced and its limits. */
    String note();

    DigestSections generate(Snapshot snapshot, String length, String focus);
}
