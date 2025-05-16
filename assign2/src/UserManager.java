import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages user accounts and authentication for the chat system.
 * This class combines user storage, authentication and account management.
 * Enhanced with token-based authentication for fault tolerance.
 */
public class UserManager {
    private static class User {
        private String username;
        private String password;
        private TokenManager currentToken;
        private String currentChatRoom;

        public User(String username, String password) {
            this.username = username;
            this.password = password;
            this.currentToken = null;
            this.currentChatRoom = null;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public boolean authenticate(String password) {
            return this.password.equals(password);
        }

        public TokenManager getCurrentToken() {
            return currentToken;
        }

        public void setCurrentToken(TokenManager token) {
            this.currentToken = token;
        }

        public String getCurrentChatRoom() {
            return currentChatRoom;
        }

        public void setCurrentChatRoom(String roomName) {
            this.currentChatRoom = roomName;
        }
    }

    private Map<String, User> users = new HashMap<>();
    private Map<String, String> tokenToUsername = new HashMap<>();
    private final String USER_FILE = "users.txt";
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();
    private final ReadWriteLock tokensLock = new ReentrantReadWriteLock();
    private final ExecutorService threadPool;

    public UserManager() {
        // Create a virtual thread per task executor
        threadPool = Executors.newVirtualThreadPerTaskExecutor();
        loadUsers();

        // Start token cleanup task
        threadPool.submit(this::cleanupExpiredTokens);
    }

    // Load users from file
    private void loadUsers() {
        threadPool.submit(() -> {
            try {
                File file = new File(USER_FILE);

                if (file.exists() == false) {
                    createDefaultUsersFile();
                }

                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;

                usersLock.writeLock().lock();
                try {
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(":");
                        if (parts.length == 2) {
                            String username = parts[0];
                            String password = parts[1];
                            users.put(username, new User(username, password));
                        }
                    }
                } finally {
                    usersLock.writeLock().unlock();
                }

                reader.close();
            } catch (IOException e) {
                System.err.println("Error loading users: " + e.getMessage());
            }
        });
    }

    // Create a default users file
    private void createDefaultUsersFile() {
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(USER_FILE));
            writer.println("luna:password123");
            writer.println("marta:password456");
            writer.println("tiago:password789");
            writer.close();
            System.out.println("Default users file created");
        } catch (IOException e) {
            System.err.println("Error creating default users file: " + e.getMessage());
        }
    }

    // Save users to file
    public void saveUsers() {
        // Use virtual thread to save users asynchronously
        threadPool.submit(() -> {
            try {
                Map<String, User> usersCopy = new HashMap<>();

                usersLock.readLock().lock();
                try {
                    usersCopy.putAll(users);
                } finally {
                    usersLock.readLock().unlock();
                }

                PrintWriter writer = new PrintWriter(new FileWriter(USER_FILE));
                for (User user : usersCopy.values()) {
                    writer.println(user.getUsername() + ":" + user.getPassword());
                }

                writer.close();
                System.out.println("Users saved: " + usersCopy.size());
            } catch (IOException e) {
                System.err.println("Error saving users: " + e.getMessage());
            }
        });
    }

    public boolean authenticateUser(String username, String password) {
        usersLock.writeLock().lock();
        try {
            User user = users.get(username);
            if (user == null) {
                user = new User(username, password);
                users.put(username, user);
                threadPool.submit(this::saveUsers);
                return true;
            } else {
                return user.authenticate(password);
            }
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    // Generate and store a token for authenticated user
    public TokenManager generateToken(String username) {
        TokenManager token = new TokenManager(username);

        usersLock.writeLock().lock();
        tokensLock.writeLock().lock();
        try {
            User user = users.get(username);
            if (user != null) {
                user.setCurrentToken(token);
                tokenToUsername.put(token.getTokenString(), username);
                return token;
            }
        } finally {
            tokensLock.writeLock().unlock();
            usersLock.writeLock().unlock();
        }

        return null;
    }

    // Validate a token and return username if valid
    public String validateToken(String tokenString) {
        tokensLock.readLock().lock();
        try {
            String username = tokenToUsername.get(tokenString);
            if (username != null) {
                usersLock.readLock().lock();
                try {
                    User user = users.get(username);
                    if (user != null && user.getCurrentToken() != null &&
                            user.getCurrentToken().getTokenString().equals(tokenString) &&
                            !user.getCurrentToken().isExpired()) {
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

    // Invalidate a user's token
    public void invalidateToken(String tokenString) {
        tokensLock.writeLock().lock();
        try {
            String username = tokenToUsername.remove(tokenString);
            if (username != null) {
                usersLock.writeLock().lock();
                try {
                    User user = users.get(username);
                    if (user != null && user.getCurrentToken() != null &&
                            user.getCurrentToken().getTokenString().equals(tokenString)) {
                        user.setCurrentToken(null);
                    }
                } finally {
                    usersLock.writeLock().unlock();
                }
            }
        } finally {
            tokensLock.writeLock().unlock();
        }
    }

    // Update user's current chat room
    public void updateUserChatRoom(String username, String roomName) {
        usersLock.writeLock().lock();
        try {
            User user = users.get(username);
            if (user != null) {
                user.setCurrentChatRoom(roomName);
            }
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    // Get user's current chat room
    public String getUserCurrentChatRoom(String username) {
        usersLock.readLock().lock();
        try {
            User user = users.get(username);
            if (user != null) {
                return user.getCurrentChatRoom();
            }
        } finally {
            usersLock.readLock().unlock();
        }
        return null;
    }

    // Periodically clean up expired tokens
    private void cleanupExpiredTokens() {
        while (true) {
            try {
                // Check every minute
                Thread.sleep(60 * 1000);

                tokensLock.writeLock().lock();
                usersLock.writeLock().lock();
                try {
                    // Identify expired tokens
                    Map<String, String> tokensCopy = new HashMap<>(tokenToUsername);
                    for (Map.Entry<String, String> entry : tokensCopy.entrySet()) {
                        String tokenString = entry.getKey();
                        String username = entry.getValue();

                        User user = users.get(username);
                        if (user != null && user.getCurrentToken() != null &&
                                user.getCurrentToken().getTokenString().equals(tokenString) &&
                                user.getCurrentToken().isExpired()) {

                            // Remove expired token
                            tokenToUsername.remove(tokenString);
                            user.setCurrentToken(null);
                            System.out.println("Expired token removed for user: " + username);
                        }
                    }
                } finally {
                    usersLock.writeLock().unlock();
                    tokensLock.writeLock().unlock();
                }
            } catch (InterruptedException e) {
                System.err.println("Token cleanup interrupted: " + e.getMessage());
                break;
            } catch (Exception e) {
                System.err.println("Error in token cleanup: " + e.getMessage());
            }
        }
    }

    // Check if a user exists
    public boolean userExists(String username) {
        usersLock.readLock().lock();
        try {
            return users.containsKey(username);
        } finally {
            usersLock.readLock().unlock();
        }
    }
}