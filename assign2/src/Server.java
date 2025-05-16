import java.io.*;
import java.util.ArrayList;
import java.io.IOException;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This program demonstrates a simple TCP/IP socket server with authentication and token-based session management.
 */
public class Server implements Runnable {

    private ArrayList<ConnectionHandler> connections;
    private ServerSocket server;
    private boolean done;
    private final ReadWriteLock connectionsLock = new ReentrantReadWriteLock();
    private final Map<String, ChatRoom> chatRooms = new HashMap<>();
    private final ReadWriteLock chatRoomsLock = new ReentrantReadWriteLock();
    private UserManager userManager;
    private final ExecutorService threadPool;
    // Map to track active sessions by token
    private final Map<String, ConnectionHandler> activeSessionsByToken = new HashMap<>();
    private final ReadWriteLock sessionsLock = new ReentrantReadWriteLock();

    public Server() {
        connections = new ArrayList<>();
        done = false;
        userManager = new UserManager();
        // Create a virtual thread per task executor
        threadPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(9999);
            System.out.println("Server started on port 9999");
            System.out.println("Waiting for client connections...");

            while (!done) {
                try {
                    Socket newClient = server.accept();
                    System.out.println("New client connected: " + newClient.getInetAddress().getHostAddress());
                    ConnectionHandler connectionHandler = new ConnectionHandler(newClient);

                    connectionsLock.writeLock().lock();
                    try {
                        connections.add(connectionHandler);
                    } finally {
                        connectionsLock.writeLock().unlock();
                    }

                    // Submit connection handler to virtual thread pool
                    threadPool.submit(connectionHandler);
                } catch (IOException e) {
                    if (!done) {
                        System.err.println("Error accepting connections: " + e.getMessage());
                        shutdown();
                    }
                }
            }
        }
        catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            shutdown();
        }
    }

    public void broadcastMessage(String message) {
        connectionsLock.readLock().lock();
        try {
            for (ConnectionHandler connection : connections) {
                if (connection != null) {
                    final ConnectionHandler conn = connection; // Create final reference for lambda
                    // Use virtual threads for sending messages to avoid blocking
                    threadPool.submit(() -> conn.sendMessage(message));
                }
            }
        } finally {
            connectionsLock.readLock().unlock();
        }
    }

    public void shutdown() {
        try {
            done = true;
            if (server != null && !server.isClosed()) {
                server.close();
            }

            connectionsLock.readLock().lock();
            try {
                for (ConnectionHandler connection : connections) {
                    final ConnectionHandler conn = connection; // Create final reference for lambda
                    if (conn != null) {
                        // Use virtual threads for shutdown operations
                        threadPool.submit(conn::shutdown);
                    }
                }
            } finally {
                connectionsLock.readLock().unlock();
            }

            // Clear active sessions
            sessionsLock.writeLock().lock();
            try {
                activeSessionsByToken.clear();
            } finally {
                sessionsLock.writeLock().unlock();
            }

            // Shutdown the thread pool gracefully
            threadPool.shutdown();
        }
        catch (IOException e) {
            System.err.println("Error shutting down server: " + e.getMessage());
        }
    }

    class ConnectionHandler implements Runnable {

        private Socket client;
        private BufferedReader clientInput;
        private PrintWriter clientOutput;
        private String clientUsername;
        private String clientPassword;
        private ChatRoom currentRoom;
        private boolean isAuthenticated = false;
        private TokenManager authToken = null;

        public ConnectionHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                clientOutput = new PrintWriter(client.getOutputStream(), true);
                clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));

                // Check first message for token resumption
                String firstMessage = clientInput.readLine();

                // Check if client is trying to resume a session with a token
                if (firstMessage != null && firstMessage.startsWith("/token ")) {
                    String token = firstMessage.substring(7).trim();
                    isAuthenticated = resumeSession(token);

                    if (!isAuthenticated) {
                        sendMessage("Invalid or expired token. Please authenticate.");
                        isAuthenticated = authenticate();
                    } else {
                        sendMessage("Session resumed. Welcome back, " + clientUsername + "!");

                        // If user was in a chat room, rejoin it
                        String previousRoom = userManager.getUserCurrentChatRoom(clientUsername);
                        if (previousRoom != null) {
                            chatRoomsLock.readLock().lock();
                            try {
                                ChatRoom room = chatRooms.get(previousRoom);
                                if (room != null) {
                                    currentRoom = room;
                                    room.addUserToChatRoom(this);
                                    sendMessage("You have been reconnected to room: " + previousRoom);
                                }
                            } finally {
                                chatRoomsLock.readLock().unlock();
                            }
                        }
                    }
                } else {
                    // Normal authentication process
                    isAuthenticated = authenticate();

                    // Process the first message if it wasn't a token command
                    if (isAuthenticated && firstMessage != null && !firstMessage.startsWith("/token ")) {
                        // Process the first message normally
                        processMessage(firstMessage);
                    }
                }

                if (!isAuthenticated) {
                    sendMessage("Authentication failed. Disconnecting...");
                    shutdown();
                    connectionsLock.writeLock().lock();
                    try {
                        connections.remove(this);
                    } finally {
                        connectionsLock.writeLock().unlock();
                    }
                    return;
                }

                System.out.println("User authenticated: " + clientUsername);

                if (firstMessage == null || firstMessage.startsWith("/token ")) {
                    sendMessage("Welcome " + clientUsername + "! You aren't in any chat room yet.");
                    sendMessage("Use /join [roomname] to enter a room. Rooms are created automatically!");                }

                String messageFromClient;
                while ((messageFromClient = clientInput.readLine()) != null) {
                    final String message = messageFromClient; // Create final copy for lambda

                    threadPool.submit(() -> processMessage(message));
                }
            }
            catch (IOException e) {
                connectionsLock.writeLock().lock();
                try {
                    connections.remove(this);
                } finally {
                    connectionsLock.writeLock().unlock();
                }

                if (isAuthenticated && authToken != null) {
                    if (currentRoom != null) {
                        userManager.updateUserChatRoom(clientUsername, currentRoom.getChatRoomName());
                        currentRoom.removeUserFromChatRoom(this);
                    }
                }

                if (clientUsername != null && !clientUsername.isEmpty()) {
                    broadcastMessage("-- User " + clientUsername + " has disconnected unexpectedly --");
                }
                shutdown();
            }
        }

        private boolean resumeSession(String tokenString) {
            String username = userManager.validateToken(tokenString);
            if (username != null) {
                clientUsername = username;

                // Register this connection as the active one for this token
                sessionsLock.writeLock().lock();
                try {
                    // If there was a previous connection with this token, disconnect it
                    ConnectionHandler existingHandler = activeSessionsByToken.get(tokenString);
                    if (existingHandler != null && existingHandler != this) {
                        existingHandler.sendMessage("Your session has been resumed from another location.");
                        existingHandler.shutdown();
                    }

                    activeSessionsByToken.put(tokenString, this);
                } finally {
                    sessionsLock.writeLock().unlock();
                }
                return true;
            }
            return false;
        }

        private void processMessage(String messageFromClient) {
            try {
                if (messageFromClient.equalsIgnoreCase("/quit")) {
                    if (currentRoom != null) {
                        currentRoom.removeUserFromChatRoom(this);
                    }
                    broadcastMessage("-- User " + clientUsername + " has left the chat --");

                    connectionsLock.writeLock().lock();
                    try {
                        connections.remove(this);
                    } finally {
                        connectionsLock.writeLock().unlock();
                    }

                    // Clean up token when user deliberately quits
                    if (authToken != null) {
                        sessionsLock.writeLock().lock();
                        try {
                            activeSessionsByToken.remove(authToken.getTokenString());
                        } finally {
                            sessionsLock.writeLock().unlock();
                        }
                        userManager.invalidateToken(authToken.getTokenString());
                    }

                    shutdown();
                }
                else if (messageFromClient.startsWith("/token")) {
                    // Client should not manually request tokens this way
                    sendMessage("Token management is handled automatically by the server.");
                }
                else if (messageFromClient.equals("/refresh_token")) {
                    // Generate a new token for the user
                    TokenManager newToken = userManager.generateToken(clientUsername);
                    if (newToken != null) {
                        // Update session mapping
                        sessionsLock.writeLock().lock();
                        try {
                            if (authToken != null) {
                                activeSessionsByToken.remove(authToken.getTokenString());
                            }
                            activeSessionsByToken.put(newToken.getTokenString(), this);
                        } finally {
                            sessionsLock.writeLock().unlock();
                        }

                        authToken = newToken;
                        sendMessage("AUTH_TOKEN:" + newToken.getTokenString());
                        sendMessage("Token refreshed. New token will expire in " + newToken.getSecondsUntilExpiration() + " seconds.");
                    } else {
                        sendMessage("Failed to refresh token.");
                    }
                }
                else if (messageFromClient.startsWith("/join")) {
                    String chatRoomName = messageFromClient.substring(6).trim();

                    if (chatRoomName.isEmpty()) {
                        sendMessage("Please provide a room name: /join [room name]");
                        return;
                    }

                    ChatRoom chatRoomToJoin;
                    boolean isNewRoom = false;
                    chatRoomsLock.readLock().lock();
                    try {
                        chatRoomToJoin = chatRooms.get(chatRoomName);

                        if (chatRoomToJoin == null) {
                            chatRoomsLock.readLock().unlock();
                            chatRoomsLock.writeLock().lock();
                            try {
                                chatRoomToJoin = new ChatRoom(chatRoomName);
                                chatRooms.put(chatRoomName, chatRoomToJoin);
                                isNewRoom = true;
                            } finally {
                                chatRoomsLock.writeLock().unlock();
                                chatRoomsLock.readLock().lock();
                            }
                        }
                    } finally {
                        chatRoomsLock.readLock().unlock();
                    }

                    if (currentRoom == chatRoomToJoin) {
                        sendMessage("You are already in this chat room :)");
                        return;
                    }

                    if (currentRoom != null) {
                        sendMessage("Leaving chat room: " + currentRoom.getChatRoomName());
                        currentRoom.removeUserFromChatRoom(this);
                    }

                    if (isNewRoom) {
                        sendMessage("Creating new chat room '" + chatRoomName + "'...");
                    }

                    currentRoom = chatRoomToJoin;
                    currentRoom.addUserToChatRoom(this);
                    userManager.updateUserChatRoom(clientUsername, chatRoomName);
                }
                else if (messageFromClient.startsWith("/rooms")) {
                    chatRoomsLock.readLock().lock();
                    try {
                        if (chatRooms.isEmpty()) {
                            sendMessage("No chat rooms available. Use /join [roomname] to create one!");
                        } else {
                            sendMessage("Available chat rooms:");
                            for (String roomName : chatRooms.keySet()) {
                                ChatRoom room = chatRooms.get(roomName);
                                sendMessage("-- " + roomName + " (" + room.getParticipantCount() + " users) --");
                            }
                        }
                    } finally {
                        chatRoomsLock.readLock().unlock();
                    }
                }
                else {
                    if (currentRoom == null) {
                        sendMessage("You are not in any chat room... use /rooms to see which rooms are available :)");
                    } else {
                        currentRoom.broadcastMessage(clientUsername + ": " + messageFromClient);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error processing message: " + e.getMessage());
            }
        }

        private boolean authenticate() {
            try {
                sendMessage("Enter your username:");
                clientUsername = clientInput.readLine();
                sendMessage("Enter your password:");
                clientPassword = clientInput.readLine();

                if (userManager.authenticateUser(clientUsername, clientPassword)) {
                    // Gera token (código existente)
                    authToken = userManager.generateToken(clientUsername);
                    // ... resto do código ...
                    return true;
                } else {
                    sendMessage("Invalid password for existing user.");
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        public void sendMessage(String message) {
            clientOutput.println(message);
        }

        public String getClientUserName() {
            return clientUsername;
        }

        public void shutdown() {
            try {
                clientInput.close();
                clientOutput.close();

                if (client != null && !client.isClosed()) {
                    client.close();
                }
            }
            catch (IOException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.run();
    }
}