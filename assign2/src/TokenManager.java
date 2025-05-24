// TokenManager.java
import java.time.Instant;
import java.util.UUID;

public class TokenManager {
    private final String tokenString;
    private final Instant expiresAt;

    /** create token with given lifetimeSeconds from now **/
    public TokenManager(long lifetimeSeconds) {
        this.tokenString = UUID.randomUUID().toString();
        this.expiresAt   = Instant.now().plusSeconds(lifetimeSeconds);
    }

    public String getTokenString() {
        return tokenString;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public long getSecondsUntilExpiration() {
        long secs = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return secs > 0 ? secs : 0;
    }
}
