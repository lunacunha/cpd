// Client.java
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client implements Runnable {

    private Socket client;
    private BufferedReader clientInput;
    private PrintWriter clientOutput;
    private final AtomicBoolean done = new AtomicBoolean(false);

    private String username, password, authToken;
    private boolean authenticated = false;
    private int maxReconnectAttempts = 5;
    private long reconnectDelay    = 2000; // ms

    @Override
    public void run() {
        try {
            connectToServer();
            while (!done.get()) {
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            System.err.println("Initial connection failed: " + e.getMessage());
            shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
        }
    }

    private void connectToServer() throws IOException {
        client = new Socket("127.0.0.1", 9999);
        clientOutput = new PrintWriter(client.getOutputStream(), true);
        clientInput  = new BufferedReader(new InputStreamReader(client.getInputStream()));

        // 1) input handler
        new Thread(new InputHandler()).start();

        // 2) server message reader
        new Thread(this::receiveMessages).start();
    }

    private void receiveMessages() {
        try {
            String line;
            while (!done.get() && (line = clientInput.readLine()) != null) {
                final String message = line;
                if (message.startsWith("AUTH_TOKEN:")) {
                    authToken     = message.substring("AUTH_TOKEN:".length());
                    System.out.println("Authenticated, token valid for 30 min.");
                    authenticated = true;
                } else {
                    System.out.println(message);
                }
            }
        } catch (IOException e) {
            if (!done.get()) {
                System.err.println("Connection lost. Reconnecting...");
                handleReconnection();
            }
        }
    }

    private void handleReconnection() {
        if (done.get()) return;
        int attempts = 0;
        boolean reconnected = false;

        while (!done.get() && attempts < maxReconnectAttempts && !reconnected) {
            try {
                attempts++;
                System.out.println("Reconnect attempt " + attempts);
                if (client != null) client.close();
                Thread.sleep(reconnectDelay);
                client = new Socket("127.0.0.1", 9999);
                clientOutput = new PrintWriter(client.getOutputStream(), true);
                clientInput  = new BufferedReader(new InputStreamReader(client.getInputStream()));

                if (authenticated && authToken != null) {
                    clientOutput.println("/token " + authToken);
                    String resp = clientInput.readLine();
                    if (resp != null && resp.contains("resumed")) {
                        System.out.println("Session resumed.");
                        reconnected = true;
                        new Thread(this::receiveMessages).start();
                    } else {
                        System.out.println("Token expired.");
                        authenticated = false;
                        authToken = null;
                    }
                } else {
                    System.out.println("Reconnected; please log in again.");
                    reconnected = true;
                    new Thread(this::receiveMessages).start();
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Reconnect failed: " + e.getMessage());
            }
        }

        if (!reconnected && !done.get()) {
            System.err.println("Unable after " + maxReconnectAttempts + " attempts. Exiting.");
            shutdown();
        }
    }

    public void shutdown() {
        done.set(true);
        try {
            if (clientInput  != null) clientInput.close();
            if (clientOutput != null) clientOutput.close();
            if (client != null && !client.isClosed()) client.close();
        } catch (IOException ignored) {}
        System.exit(0);
    }

    class InputHandler implements Runnable {
        @Override
        public void run() {
            try {
                BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
                System.out.println("Welcome! :)");
                String msg;
                while (!done.get() && (msg = console.readLine()) != null) {
                    if ("/quit".equals(msg)) {
                        System.out.println("Disconnecting...");
                        clientOutput.println("/quit");
                        console.close();
                        shutdown();
                        break;
                    } else {
                        final String toSend = msg;
                        new Thread(() -> {
                            if (clientOutput != null) {
                                clientOutput.println(toSend);
                            }
                        }).start();
                    }
                }
            } catch (IOException e) {
                System.err.println("Input error: " + e.getMessage());
                shutdown();
            }
        }
    }

    public static void main(String[] args) {
        new Client().run();
    }
}
