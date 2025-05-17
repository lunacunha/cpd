import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
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
 * Uses hashing for password storage.
 */
public class UserManager {
    private static class User {
        private String username;
        private String passwordHash; // Agora armazena o hash da senha
        private TokenManager currentToken;
        private String currentChatRoom;

        public User(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.currentToken = null;
            this.currentChatRoom = null;
        }

        public String getUsername() {
            return username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public boolean authenticate(String passwordHash) {
            return this.passwordHash.equals(passwordHash);
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

    /**
     * Cria um hash SHA-256 da string fornecida
     * @param input A string para ser transformada em hash
     * @return String hash codificada em hexadecimal
     */
    private String createHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Converter bytes para string hexadecimal
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Erro ao criar hash: " + e.getMessage());
            // Fallback para casos onde o algoritmo não está disponível (não deveria acontecer com SHA-256)
            return input;
        }
    }

    // Load users from file
    private void loadUsers() {
        threadPool.submit(() -> {
            try {
                File file = new File(USER_FILE);

                if (!file.exists()) {
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
                            String passwordHash = parts[1]; // Já é hash no arquivo
                            users.put(username, new User(username, passwordHash));
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

    // Create a default users file with hashed passwords
    private void createDefaultUsersFile() {
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(USER_FILE));
            // Armazenar senhas como hash
            writer.println("luna:" + createHash("password123"));
            writer.println("marta:" + createHash("password456"));
            writer.println("tiago:" + createHash("password789"));
            writer.close();
            System.out.println("Default users file created with hashed passwords");
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
                    writer.println(user.getUsername() + ":" + user.getPasswordHash());
                }

                writer.close();
                System.out.println("Users saved: " + usersCopy.size());
            } catch (IOException e) {
                System.err.println("Error saving users: " + e.getMessage());
            }
        });
    }

    public boolean authenticateUser(String username, String password) {
        String passwordHash = createHash(password);

        usersLock.writeLock().lock();
        try {
            User user = users.get(username);
            if (user == null) {
                user = new User(username, passwordHash);
                users.put(username, user);
                threadPool.submit(this::saveUsers);
                return true;
            } else {
                return user.authenticate(passwordHash);
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