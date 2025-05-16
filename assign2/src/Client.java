import java.net.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced client implementation with reconnection and token-based authentication
 */
public class Client implements Runnable {

    private Socket client;
    private BufferedReader clientInput;
    private PrintWriter clientOutput;
    private AtomicBoolean done;
    private final ExecutorService threadPool;

    private String username;
    private String password;
    private String authToken;
    private boolean authenticated = false;
    private int maxReconnectAttempts = 5;
    private long reconnectDelay = 2000; // milliseconds

    public Client() {
        done = new AtomicBoolean(false);
        // Create a virtual thread per task executor
        threadPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void run() {
        try {
            connectToServer();

            while (!done.get()) {
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            System.err.println("Initial connection to server failed: " + e.getMessage());
            shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
        }
    }

    private void connectToServer() throws IOException {
        client = new Socket("127.0.0.1", 9999);
        clientOutput = new PrintWriter(client.getOutputStream(), true);
        clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));

        // Start input handler to process user input
        InputHandler inputHandler = new InputHandler();
        threadPool.submit(inputHandler);

        // Start message receiver to handle server messages
        threadPool.submit(this::receiveMessages);
    }

    private void receiveMessages() {
        try {
            String inputMessage;
            while (!done.get() && (inputMessage = clientInput.readLine()) != null) {
                final String message = inputMessage; // Create final copy for lambda

                if (message.startsWith("AUTH_TOKEN:")) {
                    authToken = message.substring("AUTH_TOKEN:".length());
                    System.out.println("You are now authenticated. Token will expire in 30 minutes.");
                    authenticated = true;
                    System.out.println("Token gerado: " + authToken); // Adicione esta linha
                } else {
                    // Use virtual thread for printing normal messages
                    threadPool.submit(() -> System.out.println(message));
                }
            }
        } catch (IOException e) {
            if (!done.get()) {
                System.err.println("Connection to server lost. Attempting to reconnect...");
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
                System.out.println("Reconnection attempt " + attempts + " of " + maxReconnectAttempts);

                // Close existing connection if any
                if (client != null) {
                    try {
                        client.close();
                    } catch (IOException ignored) {}
                }

                // Pause before reconnecting
                Thread.sleep(reconnectDelay);

                // Try to establish a new connection
                client = new Socket("127.0.0.1", 9999);
                clientOutput = new PrintWriter(client.getOutputStream(), true);
                clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));

                // Check if we were authenticated before
                if (authenticated && authToken != null) {
                    // Send token to resume session
                    clientOutput.println("/token " + authToken);

                    // Wait for server response
                    String response = clientInput.readLine();
                    if (response != null && response.contains("resumed")) {
                        System.out.println("Connection restored. Your session has been resumed.");
                        reconnected = true;

                        // Start listening for messages again
                        threadPool.submit(this::receiveMessages);
                    } else {
                        System.out.println("Failed to resume session. Token may be expired.");
                        authenticated = false;
                        authToken = null;
                    }
                } else {
                    System.out.println("Reconnected to server. Please log in again.");
                    reconnected = true;

                    // Start listening for messages again
                    threadPool.submit(this::receiveMessages);
                }
            } catch (IOException e) {
                System.err.println("Reconnection attempt failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Reconnection interrupted: " + e.getMessage());
            }
        }

        if (!reconnected && !done.get()) {
            System.err.println("Failed to reconnect after " + maxReconnectAttempts + " attempts. Shutting down.");
            shutdown();
        }
    }

    public void shutdown() {
        done.set(true);
        try {
            if (clientInput != null) clientInput.close();
            if (clientOutput != null) clientOutput.close();

            if (client != null && !client.isClosed()) {
                client.close();
            }

            // Shutdown the thread pool gracefully
            threadPool.shutdown();

            System.exit(0);
        }
        catch (IOException e) {
            System.err.println("Error closing connection: " + e.getMessage());
            System.exit(1);
        }
    }

    class InputHandler implements Runnable {

        @Override
        public void run() {
            try {
                BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));

                System.out.println("Welcome! :)");

                while (!done.get()) {
                    String message = inputReader.readLine();

                    if (message == null) {
                        shutdown();
                        break;
                    }

                    if (message.equals("/quit")) {
                        System.out.println("Disconnecting from chat... See you later :)");
                        clientOutput.println("/quit");
                        inputReader.close();
                        shutdown();
                        break;
                    } else {
                        final String finalMessage = message;
                        threadPool.submit(() -> {
                            if (clientOutput != null) {
                                clientOutput.println(finalMessage);
                            }
                        });
                    }
                }
            } catch (IOException e) {
                System.err.println("Error reading input: " + e.getMessage());
                shutdown();
            }
        }
    }

    public static void main(String[] args) {
        Client client = new Client();
        client.run();
    }
}