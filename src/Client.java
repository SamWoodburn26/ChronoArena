import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;

public class Client {
    private Socket socket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;

    private String playerName;

    public Client(Socket socket, String playerName) {
        try {
            this.socket = socket;
            this.playerName = playerName;
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream = new DataInputStream(socket.getInputStream()); 
        } catch (IOException e) {
            closeEverything(socket, dataInputStream, dataOutputStream);
        }
    }

    public void sendMessage() {
        //to do
    }

    public void listenForMessage() {
        //to do
    }

    public void closeEverything(Socket socket, DataInputStream dataInputStream, DataOutputStream dataOutputStream) {
        try {
            if (dataInputStream != null) {
                dataInputStream.close();
            }
            if (dataOutputStream != null) {
                dataOutputStream.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        String userName = UUID.randomUUID().toString();
        Properties props = new Properties();
        String propertiesPath = "properties.properties";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--properties".equals(args[i])) {
                propertiesPath = args[i + 1];
            }
        }
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
        } catch (IOException ignored) {
            // Use defaults when the properties file is missing.
        }
        String serverIP = props.getProperty("serverIP", "localhost");
        int TCP_port = Integer.parseInt(props.getProperty("TCP_port", "1234"));
        int UDP_port = Integer.parseInt(props.getProperty("UDP_port", "1235"));

        Socket socket = new Socket(serverIP, TCP_port);
        Client client = new Client(socket, userName);
        client.listenForMessage();
        client.sendMessage();
        scanner.close();
    }
}
