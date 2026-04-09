import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Random;

/**
 * 
 * /@KFrancis05, help from Claude.ai
 * 
 * Stress test — spawns N bot clients that connect and move randomly.
 *
 * Usage:
 *   javac StressTest.java
 *   java StressTest <serverIP> [playerCount] [tcpPort] [udpPort]
 *
 * Examples:
 *   java StressTest 192.168.1.10
 *   java StressTest 192.168.1.10 50
 *   java StressTest 192.168.1.10 10 1234 1235
 */
public class StressTest {

    private static final int DEFAULT_COUNT    = 50;
    private static final int DEFAULT_TCP_PORT = 1234;
    private static final int DEFAULT_UDP_PORT = 1235;
    private static final int MAP_WIDTH        = 900;
    private static final int MAP_HEIGHT       = 650;

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1) {
            System.out.println("Usage: java StressTest <serverIP> [playerCount] [tcpPort] [udpPort]");
            System.exit(1);
        }

        String serverIP   = args[0];
        int    count      = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_COUNT;
        int    tcpPort    = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_TCP_PORT;
        int    udpPort    = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_UDP_PORT;

        System.out.println("Connecting " + count + " bots to " + serverIP
                + "  TCP:" + tcpPort + "  UDP:" + udpPort);

        int connected = 0;
        for (int i = 1; i <= count; i++) {
            final int botNum = i;
            try {
                BotClient bot = new BotClient(serverIP, tcpPort, udpPort, "Bot-" + botNum);
                Thread t = new Thread(bot, "bot-" + botNum);
                t.setDaemon(true);
                t.start();
                connected++;

                // Small stagger so we don't slam the server with 50 simultaneous SYNs
                Thread.sleep(50);
            } catch (IOException e) {
                System.out.println("Bot-" + botNum + " failed to connect: " + e.getMessage());
            }
        }

        System.out.println(connected + "/" + count + " bots connected. Running — press Ctrl+C to stop.");

        // Keep main thread alive; all bot threads are daemons
        Thread.currentThread().join();
    }

    // -------------------------------------------------------------------------

    static class BotClient implements Runnable {
        private final String serverIP;
        private final int    tcpPort, udpPort;
        private final String name;

        BotClient(String serverIP, int tcpPort, int udpPort, String name) throws IOException {
            this.serverIP = serverIP;
            this.tcpPort  = tcpPort;
            this.udpPort  = udpPort;
            this.name     = name;
        }

        @Override
        public void run() {
            Socket         tcp = null;
            DatagramSocket udp = null;
            try {
                // --- TCP connect + JOIN handshake ---
                tcp = new Socket(serverIP, tcpPort);
                DataOutputStream out = new DataOutputStream(tcp.getOutputStream());
                DataInputStream  in  = new DataInputStream(tcp.getInputStream());

                out.writeUTF("JOIN|" + name);
                out.flush();

                String response = in.readUTF();
                if (!response.startsWith("WELCOME|")) {
                    System.out.println(name + " rejected: " + response);
                    return;
                }
                int playerId = Integer.parseInt(response.substring(8).trim());
                System.out.println(name + " joined as id=" + playerId);

                // --- UDP socket ---
                udp = new DatagramSocket();
                InetAddress addr = InetAddress.getByName(serverIP);

                // --- TCP listener thread (drains server messages) ---
                final Socket finalTcp = tcp;
                Thread listener = new Thread(() -> {
                    try {
                        while (!finalTcp.isClosed()) {
                            in.readUTF(); // read and discard — we just keep the connection alive
                        }
                    } catch (IOException ignored) {}
                });
                listener.setDaemon(true);
                listener.start();

                // --- Random movement loop ---
                Random rng   = new Random();
                double x     = 100 + rng.nextDouble() * (MAP_WIDTH  - 200);
                double y     = 100 + rng.nextDouble() * (MAP_HEIGHT - 200);
                double dx    = (rng.nextDouble() * 2 - 1);
                double dy    = (rng.nextDouble() * 2 - 1);
                double speed = 80 + rng.nextDouble() * 80; // 80–160 px/s
                int    seq   = 0;

                long lastChange = System.currentTimeMillis();

                while (!tcp.isClosed()) {
                    // Change direction randomly every 1-3 seconds
                    if (System.currentTimeMillis() - lastChange > 1000 + rng.nextInt(2000)) {
                        dx = rng.nextDouble() * 2 - 1;
                        dy = rng.nextDouble() * 2 - 1;
                        lastChange = System.currentTimeMillis();
                    }

                    // Normalize
                    double mag = Math.hypot(dx, dy);
                    if (mag > 1e-6) { dx /= mag; dy /= mag; }

                    x += dx * speed * 0.05;
                    y += dy * speed * 0.05;

                    // Bounce off walls
                    if (x < 10)          { x = 10;          dx = Math.abs(dx); }
                    if (x > MAP_WIDTH-10) { x = MAP_WIDTH-10; dx = -Math.abs(dx); }
                    if (y < 10)          { y = 10;          dy = Math.abs(dy); }
                    if (y > MAP_HEIGHT-10){ y = MAP_HEIGHT-10;dy = -Math.abs(dy); }

                    // Send MOVE via UDP
                    String msg  = "MOVE|" + (seq++) + "|" + playerId + "|"
                            + String.format("%.1f", x) + "|" + String.format("%.1f", y);
                    byte[] data = msg.getBytes();
                    udp.send(new DatagramPacket(data, data.length, addr, udpPort));

                    Thread.sleep(50); // ~20 updates/sec
                }

            } catch (IOException | InterruptedException e) {
                System.out.println(name + " disconnected: " + e.getMessage());
            } finally {
                try { if (tcp != null) tcp.close(); } catch (IOException ignored) {}
                if (udp != null) udp.close();
            }
        }
    }
}