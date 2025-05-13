import java.io.*;
import java.util.ArrayList;
import java.io.IOException;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.HashMap;
import java.util.Map;

/**
 * This program demonstrates a simple TCP/IP socket server with authentication.
 */
public class Server implements Runnable {

    private ArrayList<ConnectionHandler> connections;
    private ServerSocket server;
    private boolean done;
    private final ReadWriteLock connectionsLock = new ReentrantReadWriteLock();
    private final Map<String, ChatRoom> chatRooms = new HashMap<>();
    private final ReadWriteLock chatRoomsLock = new ReentrantReadWriteLock();
    private UserManager userManager;

    public Server() {
        connections = new ArrayList<>();
        done = false;
        userManager = new UserManager();
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(9999);
            System.out.println("Server started on port 9999");
            System.out.println("Waiting for client connections...");

            while (!done) {
                Socket newClient = server.accept();
                System.out.println("New client connected: " + newClient.getInetAddress().getHostAddress());
                ConnectionHandler connectionHandler = new ConnectionHandler(newClient);

                connectionsLock.writeLock().lock();
                try {
                    connections.add(connectionHandler);
                } finally {
                    connectionsLock.writeLock().unlock();
                }

                Thread.ofVirtual().start(connectionHandler);
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
                    connection.sendMessage(message);
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
                    connection.shutdown();
                }
            } finally {
                connectionsLock.readLock().unlock();
            }
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

        public ConnectionHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                clientOutput = new PrintWriter(client.getOutputStream(), true);
                clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));

                // Authentication process
                isAuthenticated = authenticate();

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
                sendMessage("Welcome " + clientUsername + "! You aren't in any chat room yet.");
                sendMessage("Look at available rooms with /rooms or create one with /create [roomname]");

                String messageFromClient;
                while ((messageFromClient = clientInput.readLine()) != null) {
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

                        shutdown();
                        break;
                    }
                    else if (messageFromClient.startsWith("/register") && messageFromClient.length() > 10) {
                        // Format: /register username password
                        String[] parts = messageFromClient.substring(10).trim().split("\\s+", 2);
                        if (parts.length == 2) {
                            String newUsername = parts[0];
                            String newPassword = parts[1];

                            if (userManager.registerUser(newUsername, newPassword)) {
                                sendMessage("User " + newUsername + " registered successfully!");
                            } else {
                                sendMessage("Username " + newUsername + " already exists!");
                            }
                        } else {
                            sendMessage("Usage: /register username password");
                        }
                    }
                    else if (messageFromClient.startsWith("/create")) {
                        String newChatRoomName = messageFromClient.substring(8).trim();

                        if (newChatRoomName.isEmpty()) {
                            sendMessage("Please provide a name for the chat room: /create [roomname]");
                            continue;
                        }

                        chatRoomsLock.readLock().lock();
                        boolean roomExists;
                        try {
                            roomExists = chatRooms.containsKey(newChatRoomName);
                        } finally {
                            chatRoomsLock.readLock().unlock();
                        }

                        if (roomExists) {
                            sendMessage("Chat room '" + newChatRoomName + "' already exists. Join it with: /join " + newChatRoomName);
                            continue;
                        }

                        ChatRoom newChatRoom = new ChatRoom(newChatRoomName);

                        chatRoomsLock.writeLock().lock();
                        try {
                            chatRooms.put(newChatRoomName, newChatRoom);
                        } finally {
                            chatRoomsLock.writeLock().unlock();
                        }

                        if (currentRoom != null) {
                            currentRoom.removeUserFromChatRoom(this);
                        }

                        newChatRoom.addUserToChatRoom(this);
                        currentRoom = newChatRoom;
                        sendMessage("New chat room '" + currentRoom.getChatRoomName() + "' has been created!");
                    }
                    else if (messageFromClient.startsWith("/join")) {
                        String chatRoomName = messageFromClient.substring(6).trim();

                        if (chatRoomName.isEmpty()) {
                            sendMessage("Please provide a name of the room to join: /join [room name]");
                            continue;
                        }

                        ChatRoom chatRoomToJoin = null;
                        chatRoomsLock.readLock().lock();
                        try {
                            if (chatRooms.containsKey(chatRoomName)) {
                                chatRoomToJoin = chatRooms.get(chatRoomName);
                            }
                        } finally {
                            chatRoomsLock.readLock().unlock();
                        }

                        if (chatRoomToJoin != null) {
                            if (currentRoom == chatRoomToJoin) {
                                sendMessage("You are already in this chat room :)");
                                continue;
                            }

                            if (currentRoom != null) {
                                currentRoom.removeUserFromChatRoom(this);
                            }

                            currentRoom = chatRoomToJoin;
                            currentRoom.addUserToChatRoom(this);
                            sendMessage("You have joined the chat room: " + chatRoomName);
                        }
                        else {
                            sendMessage("Sorry... you cannot join '" + chatRoomName + "' because it does not exist.");

                            chatRoomsLock.readLock().lock();
                            try {
                                sendMessage("Available rooms: " + String.join(", ", chatRooms.keySet()));
                            } finally {
                                chatRoomsLock.readLock().unlock();
                            }
                        }
                    }
                    else if (messageFromClient.startsWith("/rooms")) {
                        chatRoomsLock.readLock().lock();
                        try {
                            if (chatRooms.isEmpty()) {
                                sendMessage("There are no chat rooms available... create one with /create [room name] :)");
                            }
                            else {
                                sendMessage("Available chat rooms:");
                                for (String roomName : chatRooms.keySet()) {
                                    ChatRoom room = chatRooms.get(roomName);
                                    sendMessage("-- " + roomName + " (" + room.getParticipantCount() + " users) --");
                                }
                                sendMessage("Join a room with /join [room name] or create a new one with /create [room name] :)");
                            }
                        } finally {
                            chatRoomsLock.readLock().unlock();
                        }
                    }
                    else {
                        // Regular message handling
                        if (currentRoom == null) {
                            sendMessage("You are not in any chat room... use /join [room name] or create one with /create [room name]");
                        } else {
                            currentRoom.broadcastMessage(clientUsername + ": " + messageFromClient);
                        }
                    }
                }
            }
            catch (IOException e) {
                connectionsLock.writeLock().lock();
                try {
                    connections.remove(this);
                } finally {
                    connectionsLock.writeLock().unlock();
                }

                if (currentRoom != null) {
                    currentRoom.removeUserFromChatRoom(this);
                }
                if (clientUsername != null && !clientUsername.isEmpty()) {
                    broadcastMessage("-- User " + clientUsername + " has disconnected unexpectedly --");
                }
                shutdown();
            }
        }

        private boolean authenticate() {
            try {
                int attempts = 0;
                final int MAX_ATTEMPTS = 3;

                while (attempts < MAX_ATTEMPTS) {
                    // Step 1: Ask for username
                    sendMessage("Enter your username:");
                    clientUsername = clientInput.readLine();

                    // Step 2: Ask for password
                    sendMessage("Enter your password:");
                    clientPassword = clientInput.readLine();

                    // Step 3: Validate credentials
                    if (userManager.authenticateUser(clientUsername, clientPassword)) {
                        return true;
                    } else {
                        attempts++;
                        sendMessage("Invalid username or password. Attempts left: " + (MAX_ATTEMPTS - attempts));
                    }
                }

                return false;
            } catch (IOException e) {
                System.err.println("Authentication error: " + e.getMessage());
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