import java.time.Instant;
import java.util.UUID;

/**
 * Represents an authentication token with expiration
 */
public class TokenManager {
    private final String tokenString;
    private final String username;
    private final Instant expirationTime;

    // default token expiration time (seconds)
    private static final long DEFAULT_EXPIRATION_TIME = 30 * 60;

    public TokenManager(String username) {
        this(username, DEFAULT_EXPIRATION_TIME);
    }

    public TokenManager(String username, long expirationSeconds) {
        this.tokenString = UUID.randomUUID().toString();
        this.username = username;
        this.expirationTime = Instant.now().plusSeconds(expirationSeconds);
    }

    public String getTokenString() {
        return tokenString;
    }

    public String getUsername() {
        return username;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expirationTime);
    }

    public long getSecondsUntilExpiration() {
        if (isExpired()) {
            return 0;
        }
        return Instant.now().until(expirationTime, java.time.temporal.ChronoUnit.SECONDS);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        TokenManager other = (TokenManager) obj;
        return tokenString.equals(other.tokenString);
    }

    @Override
    public int hashCode() {
        return tokenString.hashCode();
    }
}