// UserManager.java
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UserManager {
    private static class User {
        private final String username;
        private final String passwordHash;
        private TokenManager currentToken;
        private String currentChatRoom;

        public User(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.currentToken = null;
            this.currentChatRoom = null;
        }

        public String getUsername() { return username; }
        public String getPasswordHash() { return passwordHash; }
        public boolean authenticate(String passwordHash) { return this.passwordHash.equals(passwordHash); }
        public TokenManager getCurrentToken() { return currentToken; }
        public void setCurrentToken(TokenManager token) { this.currentToken = token; }
        public String getCurrentChatRoom() { return currentChatRoom; }
        public void setCurrentChatRoom(String roomName) { this.currentChatRoom = roomName; }
    }

    private final Map<String, User> users = new HashMap<>();
    private final Map<String, String> tokenToUsername = new HashMap<>();
    private final Set<String> activeUsers = new HashSet<>();
    private final String USER_FILE = "users.txt";
    private final ReadWriteLock usersLock       = new ReentrantReadWriteLock();
    private final ReadWriteLock tokensLock      = new ReentrantReadWriteLock();
    private final ReadWriteLock activeUsersLock = new ReentrantReadWriteLock();

    public UserManager() {
        new Thread(this::loadUsers).start();
        new Thread(this::cleanupExpiredTokens).start();
    }

    private String createHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Error creating hash: " + e.getMessage());
            return input;
        }
    }

    private void loadUsers() {
        try {
            File file = new File(USER_FILE);
            if (!file.exists()) createDefaultUsersFile();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                usersLock.writeLock().lock();
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(":");
                        if (parts.length == 2) {
                            users.put(parts[0], new User(parts[0], parts[1]));
                        }
                    }
                } finally {
                    usersLock.writeLock().unlock();
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        }
    }

    private void createDefaultUsersFile() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(USER_FILE))) {
            writer.println("luna:" + createHash("password123"));
            writer.println("marta:" + createHash("password456"));
            writer.println("tiago:" + createHash("password789"));
            System.out.println("Default users file created with hashed passwords");
        } catch (IOException e) {
            System.err.println("Error creating default users file: " + e.getMessage());
        }
    }

    public void saveUsers() {
        new Thread(() -> {
            try {
                Map<String, User> copy;
                usersLock.readLock().lock();
                try {
                    copy = new HashMap<>(users);
                } finally {
                    usersLock.readLock().unlock();
                }

                try (PrintWriter w = new PrintWriter(new FileWriter(USER_FILE))) {
                    for (User u : copy.values()) {
                        w.println(u.getUsername() + ":" + u.getPasswordHash());
                    }
                }
                System.out.println("Users saved: " + copy.size());
            } catch (IOException e) {
                System.err.println("Error saving users: " + e.getMessage());
            }
        }).start();
    }

    public boolean isUserActive(String username) {
        activeUsersLock.readLock().lock();
        try {
            return activeUsers.contains(username);
        } finally {
            activeUsersLock.readLock().unlock();
        }
    }

    public void markUserActive(String username) {
        activeUsersLock.writeLock().lock();
        try {
            activeUsers.add(username);
            System.out.println("User marked as active: " + username);
        } finally {
            activeUsersLock.writeLock().unlock();
        }
    }

    public void markUserInactive(String username) {
        activeUsersLock.writeLock().lock();
        try {
            activeUsers.remove(username);
            System.out.println("User marked as inactive: " + username);
        } finally {
            activeUsersLock.writeLock().unlock();
        }
    }

    public boolean authenticateUser(String username, String password) {
        String hash = createHash(password);
        if (isUserActive(username)) return false;

        usersLock.writeLock().lock();
        try {
            User u = users.get(username);
            if (u == null) {
                u = new User(username, hash);
                users.put(username, u);
                saveUsers();
                markUserActive(username);
                return true;
            } else if (u.authenticate(hash)) {
                markUserActive(username);
                return true;
            }
            return false;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    public TokenManager generateToken(String username) {
        TokenManager token = new TokenManager(username);
        usersLock.writeLock().lock();
        tokensLock.writeLock().lock();
        try {
            User u = users.get(username);
            if (u != null) {
                u.setCurrentToken(token);
                tokenToUsername.put(token.getTokenString(), username);
                return token;
            }
        } finally {
            tokensLock.writeLock().unlock();
            usersLock.writeLock().unlock();
        }
        return null;
    }

    public String validateToken(String tokenString) {
        tokensLock.readLock().lock();
        try {
            String username = tokenToUsername.get(tokenString);
            if (username != null) {
                usersLock.readLock().lock();
                try {
                    User u = users.get(username);
                    if (u != null && u.getCurrentToken() != null
                            && u.getCurrentToken().getTokenString().equals(tokenString)
                            && !u.getCurrentToken().isExpired()) {
                        return username;
                    }
                } finally {
                    usersLock.readLock().unlock();
                }
            }
        } finally {
            tokensLock.readLock().unlock();
        }
        return null;
    }

    public void invalidateToken(String tokenString) {
        tokensLock.writeLock().lock();
        try {
            String user = tokenToUsername.remove(tokenString);
            if (user != null) {
                usersLock.writeLock().lock();
                try {
                    User u = users.get(user);
                    if (u != null && u.getCurrentToken() != null
                            && u.getCurrentToken().getTokenString().equals(tokenString)) {
                        u.setCurrentToken(null);
                        markUserInactive(user);
                    }
                } finally {
                    usersLock.writeLock().unlock();
                }
            }
        } finally {
            tokensLock.writeLock().unlock();
        }
    }

    public void updateUserChatRoom(String username, String roomName) {
        usersLock.writeLock().lock();
        try {
            User u = users.get(username);
            if (u != null) u.setCurrentChatRoom(roomName);
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    public String getUserCurrentChatRoom(String username) {
        usersLock.readLock().lock();
        try {
            User u = users.get(username);
            return u != null ? u.getCurrentChatRoom() : null;
        } finally {
            usersLock.readLock().unlock();
        }
    }

    private void cleanupExpiredTokens() {
        while (true) {
            try {
                Thread.sleep(60_000);
                tokensLock.writeLock().lock();
                usersLock.writeLock().lock();
                try {
                    for (Iterator<Map.Entry<String,String>> it = tokenToUsername.entrySet().iterator(); it.hasNext();) {
                        Map.Entry<String,String> e = it.next();
                        TokenManager tm = users.get(e.getValue()).getCurrentToken();
                        if (tm != null && tm.getTokenString().equals(e.getKey()) && tm.isExpired()) {
                            it.remove();
                            users.get(e.getValue()).setCurrentToken(null);
                            markUserInactive(e.getValue());
                            System.out.println("Expired token removed for user: " + e.getValue());
                        }
                    }
                } finally {
                    usersLock.writeLock().unlock();
                    tokensLock.writeLock().unlock();
                }
            } catch (InterruptedException ex) {
                System.err.println("Token cleanup interrupted");
                break;
            }
        }
    }

    public boolean userExists(String username) {
        usersLock.readLock().lock();
        try {
            return users.containsKey(username);
        } finally {
            usersLock.readLock().unlock();
        }
    }
}
