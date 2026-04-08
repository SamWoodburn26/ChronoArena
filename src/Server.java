import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Server {
    private ServerSocket serverSocket;
    private DatagramSocket datagramSocket;


    public Server(ServerSocket serverSocket, DatagramSocket datagramSocket) {
        this.serverSocket = serverSocket;
        this.datagramSocket = datagramSocket;
    }

    public void startServer() {
        Thread tcpThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    DataInputStream reader = new DataInputStream(socket.getInputStream());
                    String identity = "player";//to do: change this to read from client
                    InetAddress ip = socket.getInetAddress();
                    System.out.println("Player connected: " + ip);
                    new Thread(new ClientHandler(socket, identity)).start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        //will have to add udp thread
        tcpThread.start();
    }

    public void closeServerSocket() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
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
        int SERVER_PORT_TCP = Integer.parseInt(props.getProperty("TCP_port", "1234"));
        int SERVER_PORT_UDP = Integer.parseInt(props.getProperty("UDP_port", "1235"));
        ServerSocket serverSocket = new ServerSocket(SERVER_PORT_TCP);
        DatagramSocket datagramSocket = new DatagramSocket(SERVER_PORT_UDP);
        Server server = new Server(serverSocket, datagramSocket);
        server.startServer();
        }
}
