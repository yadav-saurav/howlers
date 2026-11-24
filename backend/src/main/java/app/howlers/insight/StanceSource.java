package app.howlers.insight;

/** Where a comment's stance came from. Reader labels always win over AI suggestions, which win over rules. */
public enum StanceSource {
    /** Weighted majority of reader labels (the author's own label counts double). */
    READERS,
    /** Suggested by the configured model for a comment nobody has labelled yet (D-032). */
    AI,
    /** Rules-based suggestion from {@link TextSignals}. */
    RULES
}
