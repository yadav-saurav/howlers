package app.howlers.insight;

/**
 * How a comment relates to what it replies to (the post for top-level comments, otherwise its parent).
 * Stances are relative, not absolute truth claims: "OPPOSE" means "pushes back on the parent".
 */
public enum Stance {
    SUPPORT, OPPOSE, NEUTRAL, QUESTION, EVIDENCE, EXPERIENCE
}
