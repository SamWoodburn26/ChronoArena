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
                    // Player name is read inside ClientHandler.run() via the JOIN message
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
                    //   MOVE|seqNum|playerId|x|y
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
                        logError("udp receive error", e);
                    }
                }
            }
        });
        udpThread.setDaemon(true);
        udpThread.start();

        // --- Game loop thread (~20 ticks/sec, configurable via tick_ms property) ---
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

                // Cap dt to prevent spiral-of-death if the loop falls behind
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

        // --- Kill switch: admin console on the main thread ---
        // Commands:
        //   KILL <playerId>   forcibly remove an erratic client
        //   LIST              show all connected players
        //   STOP              shut down the server
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        System.out.println("Commands: KILL <playerId> | LIST | RESET | STOP");
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.equalsIgnoreCase("STOP")) {
                System.out.println("Shutting down server.");
                closeAll();
                break;

            } else if (line.equalsIgnoreCase("RESET")) {
                GameState.INSTANCE.resetRound();
                String notice = "SERVER: New round started — good luck!";
                for (ClientHandler ch : ClientHandler.clientHandlers) {
                    ch.sendDirect(notice);
                }
                System.out.println("Round reset. " + ClientHandler.clientHandlers.size() + " player(s) still connected.");

            } else if (line.equalsIgnoreCase("LIST")) {
                if (ClientHandler.clientHandlers.isEmpty()) {
                    System.out.println("No players connected.");
                } else {
                    System.out.println("Connected players:");
                    for (ClientHandler ch : ClientHandler.clientHandlers) {
                        System.out.println("  id=" + ch.playerId);
                    }
                }

            } else if (line.toUpperCase().startsWith("KILL ")) {
                try {
                    int targetId = Integer.parseInt(line.substring(5).trim());
                    boolean found = false;
                    for (ClientHandler ch : ClientHandler.clientHandlers) {
                        if (ch.playerId == targetId) {
                            ch.killClient("removed by server administrator");
                            found = true;
                            break;
                        }
                    }
                    if (!found) System.out.println("Player " + targetId + " not found.");
                } catch (NumberFormatException e) {
                    System.out.println("Usage: KILL <playerId>");
                }

            } else if (!line.isEmpty()) {
                System.out.println("Unknown command. Use KILL <playerId>, LIST, or STOP.");
            }
        }
        scanner.close();
    }

    public void closeAll() {
        try {
            if (serverSocket   != null) serverSocket.close();
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
            logError("could not load properties", e);
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
