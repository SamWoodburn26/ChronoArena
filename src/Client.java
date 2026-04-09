import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Date;
import java.util.Properties;
import java.util.function.Consumer;

//help from Claude.ai

public class Client {
    private Socket socket;
    private DatagramSocket udpSocket;
    private DataInputStream  dataInputStream;
    private DataOutputStream dataOutputStream;
    private String playerName;
    private int    playerId = -1;

    private InetAddress serverAddress;
    private int         udpPort;

    // Sequence number incremented for every UDP packet sent
    private int udpSeqNum = 0;

    // Guard against closeEverything() being called more than once
    private volatile boolean closed = false;

    // Callbacks wired by ChronoArenaClientUI before listenForMessage() is called
    private Consumer<String> stateCallback    = null;
    private Consumer<String> gameOverCallback = null;

    public Client(Socket socket, DatagramSocket udpSocket, String playerName,
                  InetAddress serverAddress, int udpPort) {
        try {
            this.socket        = socket;
            this.udpSocket     = udpSocket;
            this.playerName    = playerName;
            this.serverAddress = serverAddress;
            this.udpPort       = udpPort;
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream  = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            logError("client init failed", e);
            closeEverything();
        }
    }

    // -------------------------------------------------------------------------
    // Callback setters — call these BEFORE listenForMessage()
    // -------------------------------------------------------------------------

    /** Called on the TCP listener thread each time a STATE message arrives. */
    public void setStateCallback(Consumer<String> cb)    { this.stateCallback    = cb; }

    /** Called on the TCP listener thread when a GAMEOVER message arrives. */
    public void setGameOverCallback(Consumer<String> cb) { this.gameOverCallback = cb; }

    // -------------------------------------------------------------------------
    // Join handshake (TCP, synchronous)
    // -------------------------------------------------------------------------

    /**
     * Sends JOIN|playerName over TCP and blocks until WELCOME|playerId is received.
     * Must be called before listenForMessage().
     */
    public void join() {
        try {
            dataOutputStream.writeUTF("JOIN|" + playerName);
            dataOutputStream.flush();
            String response = dataInputStream.readUTF();
            if (response.startsWith("WELCOME|")) {
                playerId = Integer.parseInt(response.substring(8).trim());
                System.out.println("Joined game as: " + playerName + " (id=" + playerId + ")");
            }
        } catch (IOException e) {
            logError("join failed", e);
            closeEverything();
        }
    }

    // -------------------------------------------------------------------------
    // TCP listener (async, runs on its own thread)
    // -------------------------------------------------------------------------

    /**
     * Starts a background thread that reads server messages and dispatches them
     * to the registered callbacks.
     */
    public void listenForMessage() {
        Thread t = new Thread(() -> {
            while (!closed && socket != null && socket.isConnected()) {
                try {
                    String msg = dataInputStream.readUTF();
                    handleServerMessage(msg);
                } catch (IOException e) {
                    if (!closed) {
                        logError("lost connection to server", e);
                        closeEverything();
                    }
                    break;
                }
            }
        });
        t.setDaemon(true);  // don't prevent JVM exit when only this thread is left
        t.start();
    }

    private void handleServerMessage(String msg) {
        if (msg.startsWith("STATE|")) {
            if (stateCallback != null) stateCallback.accept(msg);

        } else if (msg.startsWith("GAMEOVER|")) {
            if (gameOverCallback != null) gameOverCallback.accept(msg);
            // Print winner to console
            String[] parts = msg.split("\\|");
            if (parts.length >= 2) {
                String[] info = parts[1].split(",");
                if (info.length >= 3) {
                    System.out.println("Game over!  Winner: " + info[1] + "  Score: " + info[2]);
                }
            }
            // Stay connected — server may issue RESET to start a new round.

        } else if (msg.startsWith("KILLED|")) {
            System.out.println("You were removed from the game by the server.");
            closeEverything();

        } else if (msg.startsWith("SERVER:")) {
            System.out.println(msg);
        }
    }

    // -------------------------------------------------------------------------
    // UDP senders (called from the Swing EDT via NetworkGamePanel)
    // -------------------------------------------------------------------------

    /** Sends a MOVE packet to the server with the player's new position. */
    public void sendMove(double x, double y) {
        String msg = "MOVE|" + (udpSeqNum++) + "|" + playerId + "|"
                + String.format("%.1f", x) + "|" + String.format("%.1f", y);
        sendUDP(msg);
    }

    /** Sends a FREEZE action packet targeting the given player ID. */
    public void sendFreeze(int targetId) {
        String msg = "ACTION|" + (udpSeqNum++) + "|" + playerId + "|FREEZE|" + targetId;
        sendUDP(msg);
    }

    private void sendUDP(String msg) {
        if (closed) return;
        try {
            byte[] data = msg.getBytes();
            DatagramPacket pkt = new DatagramPacket(data, data.length, serverAddress, udpPort);
            udpSocket.send(pkt);
        } catch (IOException e) {
            logError("udp send failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void closeEverything() {
        if (closed) return;
        closed = true;
        try {
            if (dataInputStream  != null) dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
            if (socket           != null) socket.close();
            if (udpSocket        != null && !udpSocket.isClosed()) udpSocket.close();
        } catch (IOException e) {
            logError("error closing client", e);
        }
    }

    public int getPlayerId() { return playerId; }

    static void logError(String context, Exception e) {
        try (FileWriter fw = new FileWriter("error.log", true)) {
            fw.write("[" + new Date() + "] " + context + ": " + e.getMessage() + "\n");
        } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Standalone entry point (headless test — no GUI)
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        String propertiesPath = "properties.properties";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--properties".equals(args[i])) {
                propertiesPath = args[i + 1];
            }
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
        } catch (IOException e) {
            logError("could not load properties", e);
        }

        String serverIP   = props.getProperty("serverIP",   "localhost");
        int    tcpPort    = Integer.parseInt(props.getProperty("TCP_port", "1234"));
        int    udpPort    = Integer.parseInt(props.getProperty("UDP_port", "1235"));
        String playerName = props.getProperty("player_name", "Player" + (int)(Math.random() * 1000));

        Socket         tcpSocket  = new Socket(serverIP, tcpPort);
        DatagramSocket udpSock    = new DatagramSocket();
        InetAddress    serverAddr = InetAddress.getByName(serverIP);

        Client client = new Client(tcpSocket, udpSock, playerName, serverAddr, udpPort);
        client.join();

        // Print all incoming state to stdout for headless debugging
        client.setStateCallback(msg -> System.out.println("[STATE] " + msg));
        client.setGameOverCallback(msg -> System.out.println("[GAMEOVER] " + msg));
        client.listenForMessage();

        // Keep the main thread alive so the daemon listener stays running
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }
}
