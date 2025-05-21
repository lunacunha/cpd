// Server.java
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Server implements Runnable {

    private final List<ConnectionHandler> connections = new ArrayList<>();
    private ServerSocket server;
    private boolean done = false;
    private final ReadWriteLock connectionsLock = new ReentrantReadWriteLock();
    private final Map<String, ChatRoom> chatRooms = new HashMap<>();
    private final ReadWriteLock chatRoomsLock = new ReentrantReadWriteLock();
    private final UserManager userManager = new UserManager();
    private final Map<String, ConnectionHandler> activeSessionsByToken = new HashMap<>();
    private final ReadWriteLock sessionsLock = new ReentrantReadWriteLock();

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

                    new Thread(connectionHandler).start();
                } catch (IOException e) {
                    if (!done) {
                        System.err.println("Error accepting connections: " + e.getMessage());
                        shutdown();
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            shutdown();
        }
    }

    public void broadcastMessage(String message) {
        connectionsLock.readLock().lock();
        try {
            for (ConnectionHandler connection : connections) {
                if (connection != null) {
                    new Thread(() -> connection.sendMessage(message)).start();
                }
            }
        } finally {
            connectionsLock.readLock().unlock();
        }
    }

    public void shutdown() {
        done = true;
        try {
            if (server != null && !server.isClosed()) server.close();
        } catch (IOException ignored) {}

        connectionsLock.readLock().lock();
        try {
            for (ConnectionHandler connection : connections) {
                if (connection != null) {
                    new Thread(connection::shutdown).start();
                }
            }
        } finally {
            connectionsLock.readLock().unlock();
        }

        sessionsLock.writeLock().lock();
        try {
            activeSessionsByToken.clear();
        } finally {
            sessionsLock.writeLock().unlock();
        }

        System.exit(0);
    }

    class ConnectionHandler implements Runnable {
        private final Socket client;
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
                clientInput  = new BufferedReader(new InputStreamReader(client.getInputStream()));

                String firstMessage = clientInput.readLine();

                if (firstMessage != null && firstMessage.startsWith("/token ")) {
                    String token = firstMessage.substring(7).trim();
                    isAuthenticated = resumeSession(token);

                    if (!isAuthenticated) {
                        sendMessage("Invalid or expired token. Please authenticate.");
                        isAuthenticated = authenticate();
                    } else {
                        sendMessage("Session resumed. Welcome back, " + clientUsername + "!");

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
                    isAuthenticated = authenticate();
                    if (isAuthenticated && firstMessage != null && !firstMessage.startsWith("/token ")) {
                        processMessage(firstMessage);
                    }
                }

                if (!isAuthenticated) {
                    sendMessage("Authentication failed. Connection closed.");
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
                    sendMessage("Use /join [roomname] to enter a room. Rooms are created automatically!");
                }

                String messageFromClient;
                while ((messageFromClient = clientInput.readLine()) != null) {
                    final String message = messageFromClient;
                    new Thread(() -> processMessage(message)).start();
                }
            } catch (IOException e) {
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
                    userManager.markUserInactive(clientUsername);
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
                sessionsLock.writeLock().lock();
                try {
                    ConnectionHandler existing = activeSessionsByToken.get(tokenString);
                    if (existing != null && existing != this) {
                        existing.sendMessage("Your session has been resumed from another location.");
                        existing.shutdown();
                    }
                    activeSessionsByToken.put(tokenString, this);
                } finally {
                    sessionsLock.writeLock().unlock();
                }
                userManager.markUserActive(username);
                return true;
            }
            return false;
        }

        private void processMessage(String messageFromClient) {
            try {
                if ("/quit".equalsIgnoreCase(messageFromClient)) {
                    if (currentRoom != null) currentRoom.removeUserFromChatRoom(this);
                    connectionsLock.writeLock().lock();
                    try {
                        connections.remove(this);
                    } finally {
                        connectionsLock.writeLock().unlock();
                    }
                    if (authToken != null) {
                        sessionsLock.writeLock().lock();
                        try {
                            activeSessionsByToken.remove(authToken.getTokenString());
                        } finally {
                            sessionsLock.writeLock().unlock();
                        }
                        userManager.invalidateToken(authToken.getTokenString());
                    }
                    userManager.markUserInactive(clientUsername);
                    shutdown();

                } else if (messageFromClient.startsWith("/token")) {
                    sendMessage("Token management is handled automatically by the server.");

                } else if ("/refresh_token".equals(messageFromClient)) {
                    TokenManager newToken = userManager.generateToken(clientUsername);
                    if (newToken != null) {
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

                } else if ("/leave".equals(messageFromClient)) {
                    if (currentRoom != null) {
                        ChatRoom roomToLeave = currentRoom;
                        currentRoom.removeUserFromChatRoom(this);
                        userManager.updateUserChatRoom(clientUsername, null);
                        currentRoom = null;
                        sendMessage("You have left the chat room: " + roomToLeave.getChatRoomName());
                        roomToLeave.broadcastMessage("-- User " + clientUsername + " left the room --");
                    } else {
                        sendMessage("You're not in any chat room.");
                    }

                } else if (messageFromClient.startsWith("/join")) {
                    String roomName = messageFromClient.substring(6).trim();
                    if (roomName.isEmpty()) {
                        sendMessage("Please provide a room name: /join [room name]");
                        return;
                    }

                    ChatRoom chatRoomToJoin;
                    boolean isNewRoom = false;

                    // Simplified: single write‐lock for lookup + create
                    chatRoomsLock.writeLock().lock();
                    try {
                        chatRoomToJoin = chatRooms.get(roomName);
                        if (chatRoomToJoin == null) {
                            chatRoomToJoin = new ChatRoom(roomName);
                            chatRooms.put(roomName, chatRoomToJoin);
                            isNewRoom = true;
                        }
                    } finally {
                        chatRoomsLock.writeLock().unlock();
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
                        sendMessage("Creating new chat room '" + roomName + "'...");
                    }

                    currentRoom = chatRoomToJoin;
                    currentRoom.addUserToChatRoom(this);
                    userManager.updateUserChatRoom(clientUsername, roomName);
                }
                else if ("/rooms".equals(messageFromClient)) {
                    chatRoomsLock.readLock().lock();
                    try {
                        if (chatRooms.isEmpty()) {
                            sendMessage("No chat rooms available. Use /join [roomname] to create one!");
                        } else {
                            sendMessage("Available chat rooms:");
                            for (String rn : chatRooms.keySet()) {
                                ChatRoom room = chatRooms.get(rn);
                                sendMessage("-- " + rn + " (" + room.getParticipantCount() + " users) --");
                            }
                        }
                    } finally {
                        chatRoomsLock.readLock().unlock();
                    }

                } else {
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
            final int MAX_ATTEMPTS = 3;
            int attempts = 0;
            while (attempts < MAX_ATTEMPTS) {
                try {
                    if (attempts > 0) {
                        sendMessage("Invalid credentials. Attempts remaining: " + (MAX_ATTEMPTS - attempts));
                    }
                    sendMessage("Enter your username:");
                    clientUsername = clientInput.readLine();
                    if (userManager.isUserActive(clientUsername)) {
                        sendMessage("This username is already in use. Please choose another username or try again later.");
                        continue;
                    }
                    sendMessage("Enter your password:");
                    clientPassword = clientInput.readLine();
                    if (userManager.authenticateUser(clientUsername, clientPassword)) {
                        authToken = userManager.generateToken(clientUsername);
                        if (authToken != null) {
                            sendMessage("AUTH_TOKEN:" + authToken.getTokenString());
                            sessionsLock.writeLock().lock();
                            try {
                                activeSessionsByToken.put(authToken.getTokenString(), this);
                            } finally {
                                sessionsLock.writeLock().unlock();
                            }
                            return true;
                        }
                    } else {
                        attempts++;
                    }
                } catch (IOException e) {
                    System.err.println("Authentication error: " + e.getMessage());
                    return false;
                }
            }
            sendMessage("Too many failed attempts. Disconnecting...");
            return false;
        }

        public void sendMessage(String message) {
            clientOutput.println(message);
        }

        public String getClientUserName() {
            return clientUsername;
        }

        public void shutdown() {
            try {
                if (clientInput != null) clientInput.close();
                if (clientOutput != null) clientOutput.close();
                if (client != null && !client.isClosed()) client.close();
            } catch (IOException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        new Server().run();
    }
}
