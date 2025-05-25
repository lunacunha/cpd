import java.time.Instant;
import java.util.UUID;

public class TokenManager {
    private final String tokenString;
    private final Instant expirationTime;

    public TokenManager(long lifetimeSeconds) {
        this.tokenString = UUID.randomUUID().toString();
        this.expirationTime = Instant.now().plusSeconds(lifetimeSeconds);
    }

    public String getTokenString() {
        return tokenString;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expirationTime);
    }

    public long getSecondsUntilExpiration() {
        long secs = expirationTime.getEpochSecond() - Instant.now().getEpochSecond();
        return secs > 0 ? secs : 0;
    }
}
