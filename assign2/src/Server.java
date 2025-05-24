import javax.net.ssl.*;
import java.io.*;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.locks.*;
import java.util.function.BiFunction;

public class Server {
    private static final int PORT = 9999;

    // default prompt when user omits one
    private static final String DEFAULT_AI_PROMPT =
            "You are a helpful AI assistant. Keep track of the conversation and respond concisely.";

    private final Map<String, ChatRoom> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();
    private final UserManager userManager = new UserManager();
    private final Map<String, PrintWriter> activeClients = new HashMap<>();
    private final ReadWriteLock clientsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) throws Exception {
        // Validate TLS configuration
        validateTLSConfiguration();

        SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT);

        // Configure supported protocols and cipher suites
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
            sock.setNeedClientAuth(false);  // Server-only authentication
            sock.setKeepAlive(true);
            new Thread(srv.new ConnectionHandler(sock)).start();
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

        // Validate keystore type
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
                    // already AI room: leave prompt unchanged
                    return existing;
                }
            });
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private class ConnectionHandler implements Runnable {
        private final SSLSocket sock;
        private PrintWriter out;

        ConnectionHandler(SSLSocket sock) {
            this.sock = sock;
        }

        private void send(String msg) {
            out.println(msg);
        }

        @Override
        public void run() {
            String username = null;
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    PrintWriter writer = new PrintWriter(sock.getOutputStream(), true)
            ) {
                this.out = writer;
                String line = in.readLine();
                if (line == null) return;

                // --- Authentication ---
                if (line.startsWith("/token ")) {
                    username = userManager.validateToken(line.substring(7).trim());
                    if (username != null) {
                        send("RESUMED");
                    } else {
                        send("TOKEN_INVALID");
                        return;
                    }
                } else if (line.startsWith("/login ")) {
                    String[] p = line.split(" ", 3);
                    var tm = userManager.authenticateOrRegister(p[1], p[2]);
                    if (tm == null) {
                        send("AUTH_FAILED");
                        return;
                    }
                    username = p[1];
                    send("TOKEN " + tm.getTokenString());
                } else {
                    send("INVALID_COMMAND");
                    return;
                }

                // register active client
                clientsLock.writeLock().lock();
                try {
                    activeClients.put(username, out);
                } finally {
                    clientsLock.writeLock().unlock();
                }

                // rejoin saved room if any
                ChatRoom prev = userManager.getChatRoom(username);
                if (prev != null) {
                    ChatRoom srvRoom = getOrCreateRoom(prev.getChatRoomName());
                    srvRoom.addUser(username);
                    userManager.setRoom(username, srvRoom);
                    send("-- you have rejoined the room " + srvRoom.getChatRoomName() + " --");
                }

                // --- Main loop ---
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
                        if (old != null) old.removeUser(username);
                        // join new
                        room.addUser(username);
                        userManager.setRoom(username, room);
                        send("-- you have joined the room " + room.getChatRoomName() + " --");

                    } else if (line.equals("/leave")) {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            send("NOT_IN_ROOM");
                        } else {
                            room.removeUser(username);
                            userManager.setRoom(username, null);
                            send("-- you have left the room " + room.getChatRoomName() + " --");
                        }

                    } else if (line.equals("/rooms")) {
                        roomsLock.readLock().lock();
                        try {
                            if (rooms.isEmpty()) {
                                send("No rooms available");
                            } else {
                                send("Available rooms: " + String.join(", ", rooms.keySet()));
                            }
                        } finally {
                            roomsLock.readLock().unlock();
                        }

                    } else if (line.equals("/help")) {
                        send("Commands:");
                        send("  /join <room>");
                        send("  /join AI:<name>|<prompt>    (or AI:<name> for default AI)");
                        send("  /leave");
                        send("  /rooms");
                        send("  /quit");
                        send("  /help");

                    } else if (line.equals("/quit")) {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room != null) room.removeUser(username);
                        userManager.invalidateToken(username);
                        send("Goodbye!");
                        break;

                    } else if (line.startsWith("/")) {
                        send("UNKNOWN_COMMAND");

                    } else {
                        ChatRoom room = userManager.getChatRoom(username);
                        if (room == null) {
                            send("NOT_IN_ROOM");
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

            // 1) Build the plain-text prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append(room.getPrompt()).append("\n\n");
            for (String msg : room.getHistory()) {
                prompt.append(msg).append("\n");
            }
            prompt.append("Bot: ");

            // 2) Helper to invoke ollama, merge stderr→stdout, send input, and capture all output
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

            // 3) Ensure the model is present (harmless if already pulled)
            String pullOut = runCommand.apply(
                    List.of("ollama", "pull", model),
                    new StringBuilder()
            );
            System.err.println("[ollama pull] " + pullOut.trim());

            // 4) Run the model with our assembled prompt
            String runOut = runCommand.apply(
                    List.of("ollama", "run", model),
                    prompt
            );

            // 5) Strip ANSI/control sequences and non-printables (except newline)
            String clean = runOut
                    // remove ANSI CSI sequences like ESC[?2026h or ESC[1m
                    .replaceAll("\\u001B\\[[;?0-9]*[a-zA-Z]", "")
                    // remove any other control characters except CR+LF
                    .replaceAll("[^\\x20-\\x7E\\r\\n]", "")
                    .trim();

            return clean;
        }
    }
}