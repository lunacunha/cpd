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
        while (true) {
            SSLSocket sock = (SSLSocket) serverSocket.accept();
            sock.setNeedClientAuth(false);
            sock.setKeepAlive(true);
            new Thread(srv.new ConnectionHandler(sock)).start();
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

                // If they were already in a room, tell them
                ChatRoom currentRoom = userManager.getChatRoom(username);
                if (currentRoom != null) {
                    sendMessage("JOINED " + currentRoom.getChatRoomName());
                }

                // Main loop
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("/join ")) {
                        String roomName = line.substring(6).trim();
                        ChatRoom room = getOrCreateRoom(roomName);
                        userManager.setRoom(username, room);
                        sendMessage("JOINED " + room.getChatRoomName());
                        System.err.println(username + " joined " + room.getChatRoomName());

                    } else if (line.equals("/leave")) {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            sendMessage("NOT_IN_ROOM");
                        } else {
                            userManager.setRoom(username, null);
                            sendMessage("LEFT " + room.getChatRoomName());
                            System.err.println(username + " left room " + room.getChatRoomName());
                        }

                    } else if (line.equals("/rooms")) {
                        roomsLock.readLock().lock();
                        try {
                            if (rooms.isEmpty()) {
                                sendMessage("No rooms available.");
                            } else {
                                sendMessage("Rooms: " + String.join(", ", rooms.keySet()));
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
                        userManager.invalidateToken(username);
                        sendMessage("BYE");
                        break;

                    } else if (line.startsWith("/")) {
                        sendMessage("UNKNOWN_COMMAND");

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
                    clientsLock.writeLock().lock();
                    try {
                        activeClients.remove(username);
                    } finally {
                        clientsLock.writeLock().unlock();
                    }
                    System.err.println("Cleaned up session for " + username);
                }
            }
        }
    }

    private ChatRoom getOrCreateRoom(String roomName) {
        roomsLock.writeLock().lock();
        try {
            return rooms.computeIfAbsent(roomName, ChatRoom::new);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void broadcast(ChatRoom room, String msg) {
        List<PrintWriter> targets = new ArrayList<>();
        clientsLock.readLock().lock();
        try {
            for (var e : activeClients.entrySet()) {
                ChatRoom userRoom = userManager.getChatRoom(e.getKey());
                if (room.equals(userRoom)) {
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