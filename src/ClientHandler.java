import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;

//help from Claude.ai

public class ClientHandler implements Runnable {
    public static final CopyOnWriteArrayList<ClientHandler> clientHandlers = new CopyOnWriteArrayList<>();

    private Socket socket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private String playerName;
    public int playerId = -1;

    public ClientHandler(Socket socket) {
        try {
            this.socket = socket;
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream  = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            logError("handler init failed", e);
            closeEverything();
        }
    }

    @Override
    public void run() {
        // First message must be JOIN|playerName
        try {
            String joinMsg = dataInputStream.readUTF();
            if (joinMsg.startsWith("JOIN|")) {
                playerName = joinMsg.substring(5).trim();
            } else {
                // Unexpected first message — drop this connection
                closeEverything();
                return;
            }

            playerId = GameState.INSTANCE.addPlayer(playerName);
            clientHandlers.add(this);

            sendDirect("WELCOME|" + playerId);
            broadcastMessage("SERVER: " + playerName + " joined the game");
            System.out.println("A player connected.");

        } catch (IOException e) {
            logError("join handshake failed", e);
            closeEverything();
            return;
        }

        // Keep connection alive — game loop pushes state via sendDirect()
        // We still listen in case of future client->server TCP messages
        while (socket != null && socket.isConnected()) {
            try {
                // Block-read; if client disconnects this throws and we clean up
                String msg = dataInputStream.readUTF();
                handleClientMessage(msg);
            } catch (IOException e) {
                closeEverything();
                break;
            }
        }
    }

    private void handleClientMessage(String msg) {
        // Reserved for future client->server TCP messages (e.g., chat, settings)
        // Movement and actions come via UDP, not here
    }

    /**
     * Broadcasts a message to every connected client.
     */
    public void broadcastMessage(String msg) {
        for (ClientHandler ch : clientHandlers) {
            try {
                ch.dataOutputStream.writeUTF(msg);
                ch.dataOutputStream.flush();
            } catch (IOException e) {
                logError("broadcast failed", e);
                ch.closeEverything();
            }
        }
    }

    /**
     * Sends a message directly to this client only.
     * Used by the game loop to push state updates.
     */
    public void sendDirect(String msg) {
        try {
            dataOutputStream.writeUTF(msg);
            dataOutputStream.flush();
        } catch (IOException e) {
            logError("send failed", e);
            closeEverything();
        }
    }

    /**
     * Sends a KILLED message to this specific client and removes them.
     * This is the server-side kill switch.
     */
    public void killClient(String reason) {
        sendDirect("KILLED|" + reason);
        System.out.println("A player was removed from the game.");
        closeEverything();
    }

    private void removeClientHandler() {
        if (clientHandlers.remove(this) && playerId != -1) {
            GameState.INSTANCE.removePlayer(playerId);
            broadcastMessage("SERVER: " + playerName + " has left the game");
            System.out.println("A player disconnected.");
        }
    }

    public void closeEverything() {
        removeClientHandler();
        try {
            if (dataInputStream != null)  dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
            if (socket != null)           socket.close();
        } catch (IOException e) {
            logError("error closing connection", e);
        }
    }

    static void logError(String context, Exception e) {
        try (FileWriter fw = new FileWriter("error.log", true)) {
            fw.write("[" + new Date() + "] " + context + ": " + e.getMessage() + "\n");
        } catch (IOException ignored) {}
    }
}
