import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

public class Server {
    private static final int PORT = 9999;

    // default prompt
    private static final String DEFAULT_AI_PROMPT =
            "You are a helpful AI assistant. Keep track of the conversation and respond concisely.";

    private final Map<String, ChatRoom> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();
    private final UserManager userManager = new UserManager();
    private final Map<String, PrintWriter> activeClients = new HashMap<>();
    private final ReadWriteLock clientsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) throws Exception {
        validateTLSConfiguration();

        SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT);

        serverSocket.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
        serverSocket.setEnabledCipherSuites(new String[]{
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        });

        System.err.println("TLS Server listening on port " + PORT);
        System.err.println("Using keystore: " + System.getProperty("javax.net.ssl.keyStore", "default"));

        Server srv = new Server();
        srv.userManager.setServer(srv);

        while (true) {
            SSLSocket sock = (SSLSocket) serverSocket.accept();
            sock.setNeedClientAuth(false);
            sock.setKeepAlive(true);
            Thread.startVirtualThread(srv.new ConnectionHandler(sock));
        }
    }

    private static void validateTLSConfiguration() {
        String keyStore = System.getProperty("javax.net.ssl.keyStore");
        String keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");

        if (keyStore == null || keyStore.isEmpty()) {
            System.err.println("ERROR: javax.net.ssl.keyStore system property not set!");
            System.err.println("Usage: java -Djavax.net.ssl.keyStore=server.jks " +
                    "-Djavax.net.ssl.keyStorePassword=<password> Server");
            System.exit(1);
        }

        if (keyStorePassword == null || keyStorePassword.isEmpty()) {
            System.err.println("ERROR: javax.net.ssl.keyStorePassword system property not set!");
            System.err.println("Usage: java -Djavax.net.ssl.keyStore=server.jks " +
                    "-Djavax.net.ssl.keyStorePassword=<password> Server");
            System.exit(1);
        }

        File keystoreFile = new File(keyStore);
        if (!keystoreFile.exists()) {
            System.err.println("ERROR: Keystore file not found: " + keyStore);
            System.err.println("Generate the keystore first using keytool:");
            System.err.println("keytool -genkeypair -alias server -keyalg RSA -keysize 2048 " +
                    "-validity 365 -keystore server.jks -storepass <password> " +
                    "-keypass <password> -dname \"CN=localhost\"");
            System.exit(1);
        }

        String keyStoreType = System.getProperty("javax.net.ssl.keyStoreType", "JKS");
        if (!keyStoreType.equals("JKS") && !keyStoreType.equals("PKCS12")) {
            System.err.println("WARNING: Unusual keystore type: " + keyStoreType);
        }
    }

    public ChatRoom getOrCreateRoom(String name) {
        roomsLock.writeLock().lock();
        try {
            return rooms.computeIfAbsent(name, ChatRoom::new);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    public ChatRoom getOrCreateAIRoom(String name, String prompt) {
        roomsLock.writeLock().lock();
        try {
            return rooms.compute(name, (rn, existing) -> {
                if (existing == null || !existing.isAI()) {
                    return new ChatRoom(rn, prompt);
                } else {
                    return existing;
                }
            });
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

        private void sendMessage(String msg) {
            clientOutput.println(msg);
        }

        @Override
        public void run() {
            String username = null;
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    PrintWriter writer = new PrintWriter(sock.getOutputStream(), true)
            ) {
                this.clientOutput = writer;
                String line = in.readLine();
                if (line == null) return;

                if (line.startsWith("/token ")) {
                    username = userManager.validateToken(line.substring(7).trim());
                    if (username != null) {
                        sendMessage("Resumed session :)");
                    } else {
                        sendMessage("TOKEN_INVALID");
                        return;
                    }
                } else if (line.startsWith("/login ")) {
                    String[] p = line.split(" ", 3);
                    var tm = userManager.authenticateOrRegister(p[1], p[2]);
                    if (tm == null) {
                        sendMessage("AUTH_FAILED");
                        return;
                    }
                    username = p[1];
                    sendMessage("TOKEN " + tm.getTokenString());
                } else {
                    sendMessage("INVALID_COMMAND");
                    return;
                }

                // register active client
                clientsLock.writeLock().lock();
                try {
                    activeClients.put(username, clientOutput);
                } finally {
                    clientsLock.writeLock().unlock();
                }

                // rejoin saved room if any
                ChatRoom prev = userManager.getChatRoom(username);
                if (prev != null) {
                    ChatRoom srvRoom = getOrCreateRoom(prev.getChatRoomName());
                    srvRoom.addUser(username);
                    userManager.setRoom(username, srvRoom);
                    sendMessage("-- You have rejoined the room: " + srvRoom.getChatRoomName() + " --");
                    System.out.println();
                }

                while ((line = in.readLine()) != null) {
                    if (line.startsWith("/join ")) {
                        String spec = line.substring(6).trim();
                        ChatRoom room;
                        if (spec.startsWith("AI:")) {
                            String payload = spec.substring(3);
                            String[] parts = payload.split("\\|", 2);
                            String rn    = parts[0].trim();
                            String prmpt = (parts.length > 1 && !parts[1].isEmpty())
                                    ? parts[1].trim()
                                    : DEFAULT_AI_PROMPT;
                            room = getOrCreateAIRoom(rn, prmpt);
                        } else {
                            room = getOrCreateRoom(spec);
                        }
                        // leave old
                        ChatRoom old = userManager.getChatRoom(username);
                        if (old != null) {
                            old.removeUser(username);
                        }
                        // join new
                        room.addUser(username);
                        userManager.setRoom(username, room);
                        System.out.println();
                        broadcast(room, "-- " + username + " has joined the room: " + room.getChatRoomName() + " --");
                        System.out.println();

                    } else if (line.equals("/leave")) {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            sendMessage("You are not in any room. Type /rooms to see the available chat rooms :)");
                        } else {
                            room.removeUser(username);
                            userManager.setRoom(username, null);
                            System.out.println();
                            sendMessage("-- You have left the room: " + room.getChatRoomName() + " --");
                            broadcast(room, "-- " + username + " has left the room: " + room.getChatRoomName() + " --");
                        }

                    } else if (line.equals("/rooms")) {
                        roomsLock.readLock().lock();
                        try {
                            if (rooms.isEmpty()) {
                                sendMessage("No rooms available");
                            } else {
                                sendMessage("Available rooms:");
                                for (ChatRoom room : rooms.values()) {
                                    sendMessage(String.format("- %s (%d users)",
                                            room.getChatRoomName(),
                                            room.getUserCount()
                                    ));
                                }
                            }
                        } finally {
                            roomsLock.readLock().unlock();
                        }
                    }
                    else if (line.equals("/help")) {
                        sendMessage("Commands:");
                        sendMessage("  /join <room>");
                        sendMessage("  /join AI:<name>|<prompt>    (or AI:<name> for default AI)");
                        sendMessage("  /leave");
                        sendMessage("  /rooms");
                        sendMessage("  /quit");
                        sendMessage("  /help");

                    } else if (line.equals("/quit")) {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room != null) room.removeUser(username);
                        userManager.invalidateToken(username);
                        sendMessage("Goodbye!");
                        break;

                    } else if (line.startsWith("/")) {
                        sendMessage("UNKNOWN_COMMAND");

                    } else {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            sendMessage("NOT_IN_ROOM");
                            continue;
                        }
                        String tagged = username + ": " + line;
                        room.addMessage(tagged);
                        broadcast(room, tagged);

                        if (room.isAI()) {
                            String aiResp = generateAIReply(room);
                            String botMsg = "Bot: " + aiResp;
                            room.addMessage(botMsg);
                            broadcast(room, botMsg);
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
                }
            }
        }

        private void broadcast(ChatRoom room, String msg) {
            List<PrintWriter> targets = new ArrayList<>();
            clientsLock.readLock().lock();
            try {
                for (var e : activeClients.entrySet()) {
                    if (room.hasUser(e.getKey())) {
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

        private String generateAIReply(ChatRoom room) {
            final String model = "llama3.2:1b";

            StringBuilder prompt = new StringBuilder();
            prompt.append(room.getPrompt()).append("\n\n");
            for (String msg : room.getHistory()) {
                prompt.append(msg).append("\n");
            }
            prompt.append("Bot: ");

            BiFunction<List<String>, StringBuilder, String> runCommand = (cmd, input) -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();

                    if (input.length() > 0) {
                        try (OutputStream os = proc.getOutputStream()) {
                            os.write(input.toString().getBytes());
                            os.flush();
                        }
                    }

                    StringBuilder out = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            out.append(line).append("\n");
                        }
                    }

                    proc.waitFor();
                    return out.toString();
                } catch (Exception e) {
                    return "(OLLAMA ERROR: " + e.getMessage() + ")";
                }
            };

            String pullOut = runCommand.apply(
                    List.of("ollama", "pull", model),
                    new StringBuilder()
            );
            System.err.println("[ollama pull] " + pullOut.trim());

            String runOut = runCommand.apply(
                    List.of("ollama", "run", model),
                    prompt
            );

            String clean = runOut
                    .replaceAll("\\u001B\\[[;?0-9]*[a-zA-Z]", "")
                    .replaceAll("[^\\x20-\\x7E\\r\\n]", "")
                    .trim();

            return clean;
        }
    }
}