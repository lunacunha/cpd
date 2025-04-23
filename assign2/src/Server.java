import java.io.*;
import java.util.ArrayList;
import java.io.IOException;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This program demonstrates a simple TCP/IP socket server.
 *
 * @author www.codejava.net
 */
public class Server implements Runnable {

    private ArrayList<ConnectionHandler> connections;
    private ServerSocket server;
    private boolean done;
    private ExecutorService threadPool;

    public Server() {
        connections = new ArrayList<>();
        done = false;
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(9999);
            threadPool = Executors.newCachedThreadPool();
            while (done == false) {
                Socket newClient = server.accept();
                ConnectionHandler connectionHandler = new ConnectionHandler(newClient);
                connections.add(connectionHandler);
                threadPool.execute(connectionHandler);
            }
        }
        catch (IOException e) {
            shutdown();
        }
    }

    public void broadcastMessage(String message) {
        for (ConnectionHandler connection : connections) {
            if (connection != null) {
                connection.sendMessage(message);
            }
        }
    }

     public void shutdown() {
        try {
            done = true;
            if (server.isClosed() == false) {
                server.close();
            }
            for (ConnectionHandler connection : connections) {
                connection.shutdown();
            }
        }
        catch (IOException e) {
            // ignore
        }
     }

    class ConnectionHandler implements Runnable {

        private Socket client;
        private BufferedReader clientInput;
        private PrintWriter clientOutput;
        private String clientUsername;
        private String clientPassword;

        public ConnectionHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                clientOutput = new PrintWriter(client.getOutputStream(), true);
                clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));

                clientOutput.println("Enter your username:");
                clientUsername = clientInput.readLine();

                // TODO -> the clients need to have a password to authenticate
                //clientOutput.println("Enter your password:");
                //clientPassword = clientInput.readLine();

                System.out.println("Welcome " + clientUsername + " :)");

                broadcastMessage("User " + clientUsername + " has entered the chat room :)");

                // TODO -> update messages that the user receives
            }
            catch (IOException e) {
                shutdown();
            }
        }

        public  void sendMessage(String message) {
            clientOutput.println(message);
        }

        public void shutdown() {
            try {
                clientInput.close();
                clientOutput.close();

                if (client.isClosed() == false) {
                    client.close();
                }
            }
            catch (IOException e) {
                // ignore
            }
        }
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.run();
    }

}