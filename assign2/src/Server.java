import javax.net.ssl.*;
import java.io.*;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.locks.*;

public class Server {
    private static final int PORT = 9999;
    private final Map<String, ChatRoom> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();
    private final UserManager userManager = new UserManager();
    private final Map<String, PrintWriter> activeClients = new HashMap<>();
    private final ReadWriteLock clientsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) throws Exception {
        File ks = new File("server.jks");
        if (!ks.exists()) {
            System.err.println("ERROR: server.jks keystore not found!");
            System.exit(1);
        }
        System.setProperty("javax.net.ssl.keyStore", "server.jks");
        System.setProperty("javax.net.ssl.keyStorePassword", "changeit");
        System.setProperty("javax.net.ssl.keyStoreType", "JKS");

        SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT);
        serverSocket.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
        serverSocket.setEnabledCipherSuites(new String[]{
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        });

        System.err.println("Server listening on port " + PORT);
        Server srv = new Server();
        // Pass the server instance to userManager so it can access rooms
        srv.userManager.setServer(srv);
        while (true) {
            SSLSocket sock = (SSLSocket) serverSocket.accept();
            sock.setNeedClientAuth(false);
            sock.setKeepAlive(true);
            new Thread(srv.new ConnectionHandler(sock)).start();
        }
    }

    public ChatRoom getOrCreateRoom(String roomName) {
        roomsLock.writeLock().lock();
        try {
            return rooms.computeIfAbsent(roomName, ChatRoom::new);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private class ConnectionHandler implements Runnable {
        private final SSLSocket sock;
        private PrintWriter clientOutput;

        ConnectionHandler(SSLSocket sock) {
            this.sock = sock;
        }

        public void sendMessage(String message) {
            if (clientOutput != null) {
                clientOutput.println(message);
            }
        }

        @Override
        public void run() {
            String username = null;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                 PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

                this.clientOutput = out;

                String line = in.readLine();
                if (line == null) return;

                // Authenticate or resume
                if (line.startsWith("/token ")) {
                    username = userManager.validateToken(line.substring(7).trim());
                    if (username != null) {
                        sendMessage("RESUMED");
                        System.err.println(username + " resumed session");
                    } else {
                        sendMessage("TOKEN_INVALID");
                        return;
                    }
                } else if (line.startsWith("/login ")) {
                    String[] p = line.split(" ", 3);
                    TokenManager tm = userManager.authenticateOrRegister(p[1], p[2]);
                    if (tm == null) {
                        sendMessage("AUTH_FAILED");
                        return;
                    }
                    username = p[1];
                    sendMessage("TOKEN " + tm.getTokenString());
                    System.err.println(username + " logged in or registered");
                } else {
                    sendMessage("INVALID_COMMAND");
                    return;
                }

                // Register active client
                clientsLock.writeLock().lock();
                try {
                    activeClients.put(username, out);
                } finally {
                    clientsLock.writeLock().unlock();
                }

                // Check if user was in a room and rejoin them
                ChatRoom savedRoom = userManager.getChatRoom(username);
                if (savedRoom != null) {
                    // Get or create the room on the server side
                    ChatRoom serverRoom = getOrCreateRoom(savedRoom.getChatRoomName());
                    // Add user back to the room
                    serverRoom.addUser(username);
                    // Update user's room reference to the server room instance
                    userManager.setRoom(username, serverRoom);
                    sendMessage("-- you have rejoined the room " + serverRoom.getChatRoomName() + " --");
                    System.err.println(username + " rejoined " + serverRoom.getChatRoomName() + " after reconnection");
                }

                // Main loop
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("/join ")) {
                        String roomName = line.substring(6).trim();
                        ChatRoom room = getOrCreateRoom(roomName);

                        // Remove user from previous room if they were in one
                        ChatRoom previousRoom = userManager.getChatRoom(username);
                        if (previousRoom != null) {
                            previousRoom.removeUser(username);
                        }

                        // Add user to new room
                        room.addUser(username);
                        userManager.setRoom(username, room);
                        sendMessage("-- you have joined the room " + room.getChatRoomName() + " --");
                        System.err.println(username + " joined " + room.getChatRoomName());

                    } else if (line.equals("/leave")) {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            sendMessage("NOT_IN_ROOM");
                        } else {
                            room.removeUser(username);
                            userManager.setRoom(username, null);
                            sendMessage("-- you have left the room " + room.getChatRoomName() + " --");
                            System.err.println(username + " left room " + room.getChatRoomName());
                        }

                    } else if (line.equals("/rooms")) {
                        roomsLock.readLock().lock();
                        try {
                            if (rooms.isEmpty()) {
                                sendMessage("No rooms available");
                                sendMessage("use /join [room] to create one :)");
                            } else {
                                sendMessage("Available rooms: " + String.join(", ", rooms.keySet()));
                            }
                        } finally {
                            roomsLock.readLock().unlock();
                        }

                    } else if (line.equals("/help")) {
                        sendMessage("Commands:");
                        sendMessage("  /join <room>   — join or create a room");
                        sendMessage("  /leave         — leave current room");
                        sendMessage("  /rooms         — list all rooms");
                        sendMessage("  /quit          — disconnect and delete session");
                        sendMessage("  /help          — show this message");

                    } else if (line.equals("/quit")) {
                        // Remove user from their room when they explicitly quit
                        ChatRoom currentRoom = userManager.getChatRoom(username);
                        if (currentRoom != null) {
                            currentRoom.removeUser(username);
                        }
                        userManager.invalidateToken(username);
                        sendMessage("Goodbye!");
                        break;

                    } else if (line.startsWith("/")) {
                        sendMessage("UNKNOWN_COMMAND. Type another command");

                    } else {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            sendMessage("NOT_IN_ROOM");
                        } else {
                            broadcast(room, username + ": " + line);
                        }
                    }
                }

            } catch (SocketException se) {
                System.err.println("Socket error for " + username + ": " + se.getMessage());
            } catch (IOException ioe) {
                System.err.println("I/O error for " + username + ": " + ioe.getMessage());
            } finally {
                if (username != null) {
                    // DO NOT remove user from room on disconnect - they should stay for reconnection
                    // Only remove from active clients list
                    clientsLock.writeLock().lock();
                    try {
                        activeClients.remove(username);
                    } finally {
                        clientsLock.writeLock().unlock();
                    }
                    System.err.println("Cleaned up active session for " + username + " (room membership preserved)");
                }
            }
        }
    }

    private void broadcast(ChatRoom room, String msg) {
        List<PrintWriter> targets = new ArrayList<>();
        clientsLock.readLock().lock();
        try {
            // Get all users in the room using the ChatRoom method
            Set<String> roomUsers = room.getUsers();
            for (var e : activeClients.entrySet()) {
                if (roomUsers.contains(e.getKey())) {
                    targets.add(e.getValue());
                }
            }
        } finally {
            clientsLock.readLock().unlock();
        }
        for (PrintWriter w : targets) {
            w.println(msg);
        }
    }
}