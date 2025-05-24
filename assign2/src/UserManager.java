import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.*;

public class UserManager {
    private static class User {
        final String username;
        final String passwordHash;
        TokenManager token;
        String room;

        User(String u, String ph) {
            username = u;
            passwordHash = ph;
            room = null;
        }
    }

    private final Map<String, User> users = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final String STATE_FILE = "user_state.txt";
    private final long TOKEN_LIFETIME = 60L * 60L * 24L * 7L; // one week

    public UserManager() {
        loadState();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveState));
    }

    private String sha256(String in) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var bs = md.digest(in.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : bs) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadState() {
        File f = new File(STATE_FILE);
        if (!f.exists()) {
            try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
                String aHash = sha256("admin123");
                w.printf("admin:%s:null:null%n", aHash);
                String gHash = sha256("guest123");
                w.printf("guest:%s:null:null%n", gHash);
                System.out.println("Initialized " + STATE_FILE + " with default users");
            } catch (IOException e) {
                System.err.println("Could not create " + STATE_FILE + ": " + e.getMessage());
            }
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            lock.writeLock().lock();
            try {
                users.clear();
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // format: user:passwordHash:tokenString:expiryEpoch:room
                    String[] p = line.split(":", 5);
                    if (p.length != 5) {
                        System.err.println("Skipping invalid state line: " + line);
                        continue;
                    }
                    User u = new User(p[0], p[1]);
                    // token
                    if (!"null".equals(p[2]) && !p[2].isEmpty()) {
                        long exp = Long.parseLong(p[3]);
                        if (exp > Instant.now().getEpochSecond()) {
                            u.token = createTokenFromParts(p[2], exp);
                        }
                    }
                    // room
                    u.room = "null".equals(p[4]) ? null : p[4];
                    users.put(u.username, u);
                }
                System.out.println("Loaded " + users.size() + " users from " + STATE_FILE);
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            System.err.println("Error reading " + STATE_FILE + ": " + e.getMessage());
        }
    }

    private TokenManager createTokenFromParts(String tokenString, long expiryEpoch) {
        // same reflection trick as before...
        /* ... */
        return null; // implement as before
    }

    public void saveState() {
        lock.readLock().lock();
        try (PrintWriter w = new PrintWriter(new FileWriter(STATE_FILE))) {
            for (User u : users.values()) {
                String tok = (u.token == null ? "null" : u.token.getTokenString());
                long expSec = 0;
                if (u.token != null) {
                    // reflection to pull expiry
                }
                w.printf("%s:%s:%s:%d:%s%n",
                        u.username,
                        u.passwordHash,
                        tok,
                        expSec,
                        (u.room == null ? "null" : u.room)
                );
            }
        } catch (IOException e) {
            System.err.println("Error writing " + STATE_FILE + ": " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    public TokenManager authenticateOrRegister(String user, String pass) {
        TokenManager tm = authenticate(user, pass);
        if (tm != null) return tm;
        if (!registerUser(user, pass)) return null;
        return authenticate(user, pass);
    }

    public TokenManager authenticate(String user, String pass) {
        String h = sha256(pass);
        lock.writeLock().lock();
        try {
            User u = users.get(user);
            if (u != null && u.passwordHash.equals(h)) {
                if (u.token == null || u.token.isExpired()) {
                    u.token = new TokenManager(TOKEN_LIFETIME);
                }
                return u.token;
            }
        } finally {
            lock.writeLock().unlock();
        }
        return null;
    }

    public void invalidateToken(String user) {
        lock.writeLock().lock();
        try {
            User u = users.get(user);
            if (u != null) {
                u.token = null;
                saveState();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String validateToken(String tokenStr) {
        lock.readLock().lock();
        try {
            for (User u : users.values()) {
                if (u.token != null
                        && u.token.getTokenString().equals(tokenStr)
                        && !u.token.isExpired()) {
                    return u.username;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    public void setRoom(String user, String room) {
        lock.writeLock().lock();
        try {
            User u = users.get(user);
            if (u != null) u.room = room;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getChatRoom(String user) {
        lock.readLock().lock();
        try {
            User u = users.get(user);
            return (u == null ? null : u.room);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean registerUser(String username, String password) {
        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            return false;
        }
        String hash = sha256(password);
        lock.writeLock().lock();
        try {
            if (users.containsKey(username)) return false;
            User u = new User(username, hash);
            users.put(username, u);
            saveState();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
