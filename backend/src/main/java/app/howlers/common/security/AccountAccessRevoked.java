package app.howlers.common.security;

/**
 * Published when an account loses access or changes rights: suspension, deactivation, deletion, or a role change
 * (D-030). Listeners run after the transaction commits: the status cache forgets the account and the realtime layer
 * closes the user's open WebSocket sessions, so a suspended user stops receiving live events at once.
 */
public record AccountAccessRevoked(long userId, String reason) {}
