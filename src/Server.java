import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;
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
                    // PlayerName is read inside ClientHandler.run() via JOIN message
                    ClientHandler handler = new ClientHandler(socket);
                    new Thread(handler).start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        logError("tcp accept error", e);
                    }
                }
            }
        });
        tcpThread.setDaemon(true);
        tcpThread.start();

        // --- UDP receive thread ---
        // Tracks last seen sequence number per player to drop out-of-order/duplicate packets
        ConcurrentHashMap<Integer, Integer> lastSeqPerPlayer = new ConcurrentHashMap<>();

        Thread udpThread = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!datagramSocket.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    datagramSocket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();

                    // Expected: MOVE|seqNum|playerId|x|y
                    //        or ACTION|seqNum|playerId|FREEZE|targetId
                    String[] parts = msg.split("\\|");
                    if (parts.length < 3) continue;

                    int seq, pid;
                    try {
                        seq = Integer.parseInt(parts[1]);
                        pid = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    // Drop duplicate or out-of-order packets
                    int last = lastSeqPerPlayer.getOrDefault(pid, -1);
                    if (seq <= last) continue;
                    lastSeqPerPlayer.put(pid, seq);

                    GameState.INSTANCE.actionQueue.add(msg);

                } catch (IOException e) {
                    if (!datagramSocket.isClosed()) {
                        logError("udp receive error", e);
                    }
                }
            }
        });
        udpThread.setDaemon(true);
        udpThread.start();

        // --- Game loop thread (~20 ticks/sec) ---
        Thread gameLoopThread = new Thread(() -> {
            long lastTime = System.nanoTime();
            while (!serverSocket.isClosed()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }

                long now = System.nanoTime();
                double dt = (now - lastTime) / 1_000_000_000.0;
                lastTime = now;

                // Cap dt to avoid spiral-of-death if loop falls behind
                dt = Math.min(dt, 0.1);

                GameState.INSTANCE.tick(dt);

                String stateMsg = GameState.INSTANCE.isGameOver()
                        ? GameState.INSTANCE.serializeGameOver()
                        : GameState.INSTANCE.serialize();

                for (ClientHandler ch : ClientHandler.clientHandlers) {
                    ch.sendDirect(stateMsg);
                }
            }
        });
        gameLoopThread.setDaemon(true);
        gameLoopThread.start();

        // --- Kill switch: console input on main thread ---
        // Type: KILL <playerId>   to remove an erratic client
        // Type: STOP              to shut down the server
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        System.out.println("Commands: KILL <playerId> | STOP");
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("STOP")) {
                System.out.println("Shutting down.");
                closeServerSocket();
                break;
            } else if (line.toUpperCase().startsWith("KILL ")) {
                try {
                    int targetId = Integer.parseInt(line.substring(5).trim());
                    boolean found = false;
                    for (ClientHandler ch : ClientHandler.clientHandlers) {
                        if (ch.playerId == targetId) {
                            ch.killClient("removed by server");
                            found = true;
                            break;
                        }
                    }
                    if (!found) System.out.println("Player not found.");
                } catch (NumberFormatException e) {
                    System.out.println("Usage: KILL <playerId>");
                }
            } else {
                System.out.println("Unknown command. Use KILL <playerId> or STOP.");
            }
        }
        scanner.close();
    }

    public void closeServerSocket() {
        try {
            if (serverSocket != null)   serverSocket.close();
            if (datagramSocket != null) datagramSocket.close();
        } catch (IOException e) {
            logError("error closing server", e);
        }
    }

    static void logError(String context, Exception e) {
        try (FileWriter fw = new FileWriter("error.log", true)) {
            fw.write("[" + new Date() + "] " + context + ": " + e.getMessage() + "\n");
        } catch (IOException ignored) {}
    }

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
            // Proceed with defaults
        }

        int tcpPort = Integer.parseInt(props.getProperty("TCP_port", "1234"));
        int udpPort = Integer.parseInt(props.getProperty("UDP_port", "1235"));

        ServerSocket serverSocket   = new ServerSocket(tcpPort);
        DatagramSocket datagramSocket = new DatagramSocket(udpPort);

        Server server = new Server(serverSocket, datagramSocket);
        server.startServer();
    }
}
