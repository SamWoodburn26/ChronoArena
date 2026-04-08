import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;

public class ClientHandler implements Runnable {
    public static java.util.List<ClientHandler> clientHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();;
    private Socket socket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private String playerName;

    public ClientHandler(Socket socket, String playerName) {
        try {
            this.socket = socket;
            this.playerName = playerName;
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            closeEverything(socket, dataInputStream, dataOutputStream);
        }
    }

    @Override
    public void run() {
        String messageFromClient;
        while (socket.isConnected()) {
            try {

            } catch (Exception e) {
                closeEverything(socket, dataInputStream, dataOutputStream);
                break;
            }
        }
    }

    public void broadcastMessage(String messageToSend) {
        //to do
    }
    
    public void removeClientHandler() {
        clientHandlers.remove(this);
        broadcastMessage("SERVER: " + playerName + " has left the game");
        System.out.println("A player has disconnected");
    }

    public void closeEverything(Socket socket, DataInputStream dataInputStream, DataOutputStream dataOutputStream) {
        removeClientHandler();
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
}
