import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UserManager {
    private static class User {
        final String username;
        final String passwordHash;
        TokenManager token;
        ChatRoom room;

        User(String name, String pass) {
            username = name;
            passwordHash = pass;
            room = null;
        }
    }

    private final Map<String, User> users = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final String STATE_FILE = "user_state.txt";
    private final long TOKEN_LIFETIME = 60L * 60L * 24L * 7L; // one week
    private Server server; // Reference to server for room coordination

    public UserManager() {
        loadState();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveState));
    }

    public void setServer(Server server) {
        this.server = server;
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
        File stateFile = new File(STATE_FILE);
        if (!stateFile.exists()) {
            try (PrintWriter w = new PrintWriter(new FileWriter(stateFile))) {
                String aHash = sha256("admin123");
                w.printf("admin:%s:null:null:null%n", aHash);
                String gHash = sha256("guest123");
                w.printf("guest:%s:null:null:null%n", gHash);
                System.out.println("Initialized " + STATE_FILE + " with default users");
            } catch (IOException e) {
                System.err.println("Could not create " + STATE_FILE + ": " + e.getMessage());
            }
        }
        try (BufferedReader r = new BufferedReader(new FileReader(stateFile))) {
            String line;
            lock.writeLock().lock();
            try {
                users.clear();
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // format: username:passwordHash:tokenString:expiryEpoch:roomName
                    String[] p = line.split(":", 5);
                    if (p.length != 5) {
                        System.err.println("Skipping invalid state line: " + line);
                        continue;
                    }
                    User user = new User(p[0], p[1]);
                    // token
                    if (!"null".equals(p[2]) && !p[2].isEmpty()) {
                        long exp = Long.parseLong(p[3]);
                        if (exp > Instant.now().getEpochSecond()) {
                            user.token = createTokenFromParts(p[2], exp);
                        }
                    }
                    // room - create a placeholder room that will be resolved when server is available
                    if (!"null".equals(p[4]) && !p[4].isEmpty()) {
                        // Create a placeholder ChatRoom - this will be replaced with the actual server room when user reconnects
                        user.room = new ChatRoom(p[4]);
                    }
                    users.put(user.username, user);
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
        try {
            // Use reflection to create TokenManager with specific token and expiry
            Constructor<TokenManager> constructor = TokenManager.class.getDeclaredConstructor(long.class);
            TokenManager tm = constructor.newInstance(0L); // Create with 0 seconds (will be overridden)

            // Override the tokenString field
            Field tokenField = TokenManager.class.getDeclaredField("tokenString");
            tokenField.setAccessible(true);
            tokenField.set(tm, tokenString);

            // Override the expiresAt field
            Field expiresAtField = TokenManager.class.getDeclaredField("expiresAt");
            expiresAtField.setAccessible(true);
            expiresAtField.set(tm, Instant.ofEpochSecond(expiryEpoch));

            return tm;
        } catch (Exception e) {
            System.err.println("Error creating token from parts: " + e.getMessage());
            return null;
        }
    }

    public void saveState() {
        lock.readLock().lock();
        try (PrintWriter w = new PrintWriter(new FileWriter(STATE_FILE))) {
            for (User user : users.values()) {
                String tok = (user.token == null ? "null" : user.token.getTokenString());
                long expSec = 0;
                if (user.token != null) {
                    try {
                        Field expiresAtField = TokenManager.class.getDeclaredField("expiresAt");
                        expiresAtField.setAccessible(true);
                        Instant expiresAt = (Instant) expiresAtField.get(user.token);
                        expSec = expiresAt.getEpochSecond();
                    } catch (Exception e) {
                        System.err.println("Error getting token expiry: " + e.getMessage());
                    }
                }
                String roomName = (user.room == null ? "null" : user.room.getChatRoomName());
                w.printf("%s:%s:%s:%d:%s%n",
                        user.username,
                        user.passwordHash,
                        tok,
                        expSec,
                        roomName
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
                // Also clear room when explicitly invalidating token (user quit)
                if (u.room != null) {
                    u.room = null;
                }
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

    public void setRoom(String user, ChatRoom room) {
        lock.writeLock().lock();
        try {
            User u = users.get(user);
            if (u != null) {
                u.room = room;
                saveState();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ChatRoom getChatRoom(String user) {
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