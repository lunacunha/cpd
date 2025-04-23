import java.net.*;
import java.io.*;
 
/**
 * This program demonstrates a simple TCP/IP socket client.
 *
 * @author www.codejava.net
 */
public class Client implements Runnable {

    private Socket client;
    private BufferedReader clientInput;
    private PrintWriter clientOutput;
    private boolean done;

    @Override
    public void run() {
        try {
            Socket client = new Socket("127.0.0.1", 9999);
            clientOutput = new PrintWriter(client.getOutputStream(), true);
            clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));

            InputHandler inputHandler = new InputHandler();
            Thread newThread = new Thread(inputHandler);
            newThread.start();

            String inputMessage;
            while ((inputMessage = clientInput.readLine()) != null) {
                System.out.println(inputMessage);
            }
        }
        catch (IOException e) {
            // TODO -> handle
        }
    }

    public void shutdown() {
        done = true;
        try {
            clientInput.close();
            clientOutput.close();

            if (client.isClosed() == false) {

            }
        }
        catch (IOException e) {
            // ignore
        }
    }

    class InputHandler implements Runnable {

        @Override
        public void run() {
            try {
                BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));

                while (done == false) {
                    String message = inputReader.readLine();
                    if (message.equals("/quit")) {
                        inputReader.close();
                        shutdown();
                    }
                    else {
                        clientOutput.println(message);
                    }
                }
            }
            catch (IOException e) {
                shutdown();
            }
        }
    }

    public static void main(String[] args) {
        Client client = new Client();
        client.run();
    }

}