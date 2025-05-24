import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;

public class Client {
    private static String username;
    private static String savedToken = null;
    private static Path sessionFile;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY_MS = 2000;
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 9999;
    private static volatile boolean done;
    private static volatile boolean quit;

    public static void main(String[] args) throws Exception {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Username> ");
        username = console.readLine().trim();
        sessionFile = Paths.get("session_" + username + ".token");
        if (Files.exists(sessionFile)) {
            savedToken = new String(Files.readAllBytes(sessionFile)).trim();
        }

        // Validate TLS configuration
        validateTLSConfiguration();

        if (!serverAvailable()) {
            System.err.printf("ERROR: Cannot connect to server at %s:%d%n", SERVER_HOST, SERVER_PORT);
            System.exit(1);
        }

        if (savedToken == null) {
            login(console, username);
        }

        while (true) {
            try {
                if (connectAndChat(console)) break;
            } catch (Exception e) {
                System.err.println("Connection dropped: " + e.getMessage());
                System.out.println("Reconnecting...");
                boolean ok = false;
                for (int i = 1; i <= MAX_RECONNECT_ATTEMPTS; i++) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                        System.out.println("Attempt " + i + "...");
                        if (connectAndChat(console)) System.exit(0);
                        ok = true;
                        break;
                    } catch (Exception ex) {
                        System.err.println("  failed: " + ex.getMessage());
                    }
                }
                if (!ok) {
                    System.err.println("Could not reconnect.");
                    break;
                }
            }
        }
    }

    private static void validateTLSConfiguration() {
        String trustStore = System.getProperty("javax.net.ssl.trustStore");
        String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");

        if (trustStore == null || trustStore.isEmpty()) {
            System.err.println("ERROR: javax.net.ssl.trustStore system property not set!");
            System.err.println("Usage: java -Djavax.net.ssl.trustStore=truststore.jks " +
                    "-Djavax.net.ssl.trustStorePassword=<password> Client");
            System.exit(1);
        }

        if (trustStorePassword == null || trustStorePassword.isEmpty()) {
            System.err.println("ERROR: javax.net.ssl.trustStorePassword system property not set!");
            System.err.println("Usage: java -Djavax.net.ssl.trustStore=truststore.jks " +
                    "-Djavax.net.ssl.trustStorePassword=<password> Client");
            System.exit(1);
        }

        File truststoreFile = new File(trustStore);
        if (!truststoreFile.exists()) {
            System.err.println("ERROR: Truststore file not found: " + trustStore);
            System.err.println("Generate the truststore first by exporting the server certificate:");
            System.err.println("1. Export server certificate: keytool -exportcert -alias server " +
                    "-keystore server.jks -storepass <server_password> -file server.crt");
            System.err.println("2. Import into truststore: keytool -importcert -alias server " +
                    "-file server.crt -keystore truststore.jks -storepass <trust_password>");
            System.exit(1);
        }

        // Validate truststore type
        String trustStoreType = System.getProperty("javax.net.ssl.trustStoreType", "JKS");
        if (!trustStoreType.equals("JKS") && !trustStoreType.equals("PKCS12")) {
            System.err.println("WARNING: Unusual truststore type: " + trustStoreType);
        }

        System.out.println("Using truststore: " + trustStore);
    }

    private static boolean serverAvailable() {
        try {
            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sock = (SSLSocket) sf.createSocket(SERVER_HOST, SERVER_PORT)) {
                configureSocket(sock);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void login(BufferedReader console, String user) throws Exception {
        System.out.print("Password> ");
        String pass = console.readLine().trim();

        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket sock = (SSLSocket) sf.createSocket(SERVER_HOST, SERVER_PORT)) {
            configureSocket(sock);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);

            out.println("/login " + user + " " + pass);
            String resp = in.readLine();
            if (resp != null && resp.startsWith("TOKEN ")) {
                savedToken = resp.substring(6).trim();
                Files.write(sessionFile, savedToken.getBytes());
                System.out.println("Logged in. Token saved to " + sessionFile);
            } else {
                System.err.println("Login failed: " + resp);
                System.exit(1);
            }
        }
    }

    private static boolean connectAndChat(BufferedReader console) throws Exception {
        done = false;
        quit = false;

        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket sock = (SSLSocket) sf.createSocket(SERVER_HOST, SERVER_PORT)) {
            configureSocket(sock);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);

            out.println("/token " + savedToken);
            String welcome = in.readLine();
            if (welcome == null || welcome.equals("TOKEN_INVALID")) {
                System.err.println("Token invalid. Re-login.");
                Files.deleteIfExists(sessionFile);
                savedToken = null;
                login(console, username);
                return false;
            }
            System.out.println(welcome);

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println(line);
                    }
                } catch (IOException e) {
                    if (!done) System.err.println("Disconnected from server.");
                } finally {
                    done = true;
                }
            });
            reader.setDaemon(true);
            reader.start();

            printHelp();
            while (!done) {
                if (!console.ready()) {
                    Thread.sleep(100);
                    continue;
                }
                String msg = console.readLine();
                if (msg == null) continue;

                switch (msg.trim()) {
                    case "/help":
                        printHelp();
                        continue;
                    case "/quit":
                        out.println("/quit");
                        Files.deleteIfExists(sessionFile);
                        System.out.println("Session deleted and quit.");
                        quit = true;
                        break;
                    default:
                        out.println(msg);
                        continue;
                }
                break;
            }

            done = true;
            return quit;
        }
    }

    private static void configureSocket(SSLSocket sock) throws IOException {
        sock.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        sock.setEnabledCipherSuites(new String[]{
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        });
        sock.setKeepAlive(true);
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  /join <room>      — join or create a room");
        System.out.println("  /join AI:<room>   — join or create a room with chat bot");
        System.out.println("  /leave            — leave current room");
        System.out.println("  /rooms            — list all rooms");
        System.out.println("  /quit             — exit client");
        System.out.println("  /help             — show this list");
    }
}