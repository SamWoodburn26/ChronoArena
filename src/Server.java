import javax.swing.SwingUtilities;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

//help from Claude.ai

public class Server {
    private ServerSocket serverSocket;
    private DatagramSocket datagramSocket;

    public Server(ServerSocket serverSocket, DatagramSocket datagramSocket) {
        this.serverSocket   = serverSocket;
        this.datagramSocket = datagramSocket;
    }

    public void startServer() {

        // --- TCP accept thread ---
        Thread tcpThread = new Thread(() -> {
            System.out.println("Server started. Waiting for players...");
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    // Player name is read inside ClientHandler.run() via the JOIN message
                    ClientHandler handler = new ClientHandler(socket);
                    new Thread(handler).start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        System.err.println("tcp accept error: " + e.getMessage());
                    }
                }
            }
        });
        tcpThread.setDaemon(true);
        tcpThread.start();

        // --- UDP receive thread ---
        // Tracks the last seen sequence number per player to drop out-of-order/duplicate packets.
        ConcurrentHashMap<Integer, Integer> lastSeqPerPlayer = new ConcurrentHashMap<>();

        Thread udpThread = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!datagramSocket.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    datagramSocket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();

                    // Expected formats:
                    //   MOVE|seqNum|playerId|dx|dy
                    //   ACTION|seqNum|playerId|FREEZE|targetId
                    String[] parts = msg.split("\\|");
                    if (parts.length < 3) continue;

                    int seq, pid;
                    try {
                        seq = Integer.parseInt(parts[1]);
                        pid = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    // Silently drop duplicate or out-of-order packets
                    int last = lastSeqPerPlayer.getOrDefault(pid, -1);
                    if (seq <= last) continue;
                    lastSeqPerPlayer.put(pid, seq);

                    GameState.INSTANCE.actionQueue.add(msg);

                } catch (IOException e) {
                    if (!datagramSocket.isClosed()) {
                        System.err.println("udp receive error: " + e.getMessage());
                    }
                }
            }
        });
        udpThread.setDaemon(true);
        udpThread.start();

        // --- Game loop thread (~20 ticks/sec) ---
        Thread gameLoopThread = new Thread(() -> {
            long lastTime = System.nanoTime();
            boolean gameOverBroadcast = false;
            while (!serverSocket.isClosed()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }

                long now = System.nanoTime();
                double dt = (now - lastTime) / 1_000_000_000.0;
                lastTime = now;

                // Cap dt to prevent spiral-of-death if the loop falls behind
                dt = Math.min(dt, 0.1);

                GameState.INSTANCE.tick(dt);

                // Broadcast any events queued during this tick (e.g. freeze rays)
                String event;
                while ((event = GameState.INSTANCE.pendingBroadcasts.poll()) != null) {
                    for (ClientHandler ch : ClientHandler.clientHandlers) {
                        ch.sendDirect(event);
                    }
                }

                if (GameState.INSTANCE.isGameOver()) {
                    if (!gameOverBroadcast) {
                        String gameOverMsg = GameState.INSTANCE.serializeGameOver();
                        for (ClientHandler ch : ClientHandler.clientHandlers) {
                            ch.sendDirect(gameOverMsg);
                        }
                        gameOverBroadcast = true;
                        System.out.println("Game over broadcast sent.");
                    }
                } else {
                    gameOverBroadcast = false;  // reset for next round
                    String stateMsg = GameState.INSTANCE.serialize();
                    for (ClientHandler ch : ClientHandler.clientHandlers) {
                        ch.sendDirect(stateMsg);
                    }
                }
            }
        });
        gameLoopThread.setDaemon(true);
        gameLoopThread.start();

        // --- Server GUI (main thread hands off to EDT) ---
        final Server self = this;
        SwingUtilities.invokeLater(() -> new ServerUI(self).show());
    }

    public void closeAll() {
        try {
            if (serverSocket   != null) serverSocket.close();
            if (datagramSocket != null) datagramSocket.close();
        } catch (IOException e) {
            System.err.println("error closing server: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.net.preferIPv4Stack", "true");
        // Allow: java Server --properties /path/to/game.properties
        String propertiesPath = "properties.properties";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--properties".equals(args[i])) {
                propertiesPath = args[i + 1];
            }
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
            System.out.println("Loaded properties from: " + propertiesPath);
        } catch (IOException e) {
            System.out.println("Warning: could not load '" + propertiesPath + "', using defaults.");
        }

        // Configure game state BEFORE opening sockets so no client can join
        // before the game world is fully initialized.
        GameState.INSTANCE.configure(props);

        int tcpPort = Integer.parseInt(props.getProperty("TCP_port", "1234"));
        int udpPort = Integer.parseInt(props.getProperty("UDP_port", "1235"));

        ServerSocket   serverSocket   = new ServerSocket(tcpPort);
        DatagramSocket datagramSocket = new DatagramSocket(udpPort);

        System.out.println("TCP listening on port " + tcpPort);
        System.out.println("UDP listening on port " + udpPort);

        Server server = new Server(serverSocket, datagramSocket);
        server.startServer();
    }
}
