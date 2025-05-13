import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages user accounts and authentication for the chat system.
 * This class combines user storage, authentication and account management.
 */
public class UserManager {
    private static class User {
        private String username;
        private String password;

        public User(String username, String password) {
            this.username = username;
            this.password = password;
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
    }

    private Map<String, User> users = new HashMap<>();
    private final String USER_FILE = "users.txt";
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

    public UserManager() {
        loadUsers();
    }

    // Load users from file
    private void loadUsers() {
        try {
            File file = new File(USER_FILE);

            // Create default users file if it doesn't exist
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
                        String password = parts[1];
                        users.put(username, new User(username, password));
                    }
                }
            } finally {
                usersLock.writeLock().unlock();
            }

            reader.close();
            System.out.println("Users loaded: " + users.size());
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        }
    }

    // Create a default users file with some sample users
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
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(USER_FILE));

            usersLock.readLock().lock();
            try {
                for (User user : users.values()) {
                    writer.println(user.getUsername() + ":" + user.getPassword());
                }
            } finally {
                usersLock.readLock().unlock();
            }

            writer.close();
            System.out.println("Users saved: " + users.size());
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        }
    }

    // Register a new user
    public boolean registerUser(String username, String password) {
        usersLock.writeLock().lock();
        try {
            if (users.containsKey(username)) {
                return false; // User already exists
            }

            User newUser = new User(username, password);
            users.put(username, newUser);
        } finally {
            usersLock.writeLock().unlock();
        }

        saveUsers();
        return true;
    }

    // Authenticate a user
    public boolean authenticateUser(String username, String password) {
        usersLock.readLock().lock();
        try {
            User user = users.get(username);
            return user != null && user.authenticate(password);
        } finally {
            usersLock.readLock().unlock();
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