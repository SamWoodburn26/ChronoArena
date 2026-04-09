import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

//help from Claude.ai

public class ClientHandler implements Runnable {
    public static final CopyOnWriteArrayList<ClientHandler> clientHandlers = new CopyOnWriteArrayList<>();

    private Socket socket;
    private DataInputStream  dataInputStream;
    private DataOutputStream dataOutputStream;
    private String playerName;
    public  int    playerId = -1;

    // Guards against closeEverything() being called more than once from concurrent threads
    private volatile boolean closed = false;

    public ClientHandler(Socket socket) {
        try {
            this.socket           = socket;
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream  = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            System.err.println("handler init failed: " + e.getMessage());
            closeEverything();
        }
    }

    @Override
    public void run() {
        // First message from the client must be:  JOIN|playerName
        try {
            String joinMsg = dataInputStream.readUTF();
            if (joinMsg.startsWith("JOIN|")) {
                playerName = joinMsg.substring(5).trim();
                if (playerName.isEmpty()) playerName = "Player";
            } else {
                // Unexpected first message — reject the connection
                closeEverything();
                return;
            }

            playerId = GameState.INSTANCE.addPlayer(playerName);
            clientHandlers.add(this);

            // Tell the new client its assigned ID
            sendDirect("WELCOME|" + playerId);

            // Immediately push the current game state so the client has something
            // to render before the next tick broadcast arrives
            sendDirect(GameState.INSTANCE.serialize());

            broadcastMessage("SERVER: " + playerName + " joined the game");
            System.out.println("Player " + playerId + " (" + playerName + ") connected.");

        } catch (IOException e) {
            System.err.println("join handshake failed: " + e.getMessage());
            closeEverything();
            return;
        }

        // Keep the TCP connection alive.  The game loop pushes state via sendDirect().
        // We still drain the read side so we can detect disconnects promptly, and to
        // support any future client->server TCP messages (e.g. chat).
        while (!closed && socket != null && socket.isConnected()) {
            try {
                String msg = dataInputStream.readUTF();
                handleClientMessage(msg);
            } catch (IOException e) {
                // Client disconnected or stream error
                closeEverything();
                break;
            }
        }
    }

    private void handleClientMessage(String msg) {
        // Reserved for future client->server TCP messages (chat, settings, etc.)
        // Movement and actions arrive via UDP, not here.
    }

    /**
     * Broadcasts a message to every currently connected client.
     */
    public void broadcastMessage(String msg) {
        for (ClientHandler ch : clientHandlers) {
            try {
                synchronized (ch) {
                    ch.dataOutputStream.writeUTF(msg);
                    ch.dataOutputStream.flush();
                }
            } catch (IOException e) {
                ch.closeEverything();
            }
        }
    }

    /**
     * Sends a message directly to this client only.
     * Called by the game loop to push state updates; synchronized so the game
     * loop thread and any other sender don't interleave writes.
     */
    public synchronized void sendDirect(String msg) {
        if (closed) return;
        try {
            dataOutputStream.writeUTF(msg);
            dataOutputStream.flush();
        } catch (IOException e) {
            closeEverything();
        }
    }

    /**
     * Server-side kill switch: sends a KILLED notice to the client and removes them.
     */
    public void killClient(String reason) {
        sendDirect("KILLED|" + reason);
        System.out.println("Player " + playerId + " was killed by server: " + reason);
        closeEverything();
    }

    private void removeClientHandler() {
        if (clientHandlers.remove(this) && playerId != -1) {
            GameState.INSTANCE.removePlayer(playerId);
            broadcastMessage("SERVER: " + playerName + " has left the game");
            System.out.println("Player " + playerId + " (" + playerName + ") disconnected.");
        }
    }

    public void closeEverything() {
        if (closed) return;   // already closing — prevent recursive/concurrent double-close
        closed = true;
        removeClientHandler();
        try {
            if (dataInputStream  != null) dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
            if (socket           != null) socket.close();
        } catch (IOException e) {
            System.err.println("error closing connection for player " + playerId + ": " + e.getMessage());
        }
    }
}
