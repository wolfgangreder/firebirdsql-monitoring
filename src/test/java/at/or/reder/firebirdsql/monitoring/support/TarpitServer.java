package at.or.reder.firebirdsql.monitoring.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TCP server that accepts connections and never answers, like a hung database server or a
 * connection whose peer silently vanished. Any client read without a timeout blocks forever.
 */
public final class TarpitServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    public TarpitServer() {
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("cannot start tarpit server", e);
        }
        Thread acceptor = new Thread(this::acceptLoop, "tarpit-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                accepted.add(server.accept()); // hold it open, never read or write
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // best effort
        }
        for (Socket s : accepted) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
