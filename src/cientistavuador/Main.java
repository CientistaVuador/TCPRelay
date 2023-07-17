/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <https://unlicense.org>
 */
package cientistavuador;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author Cien
 */
public class Main {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private static String format(long bytes) {
        int unit = 1;
        String sufix = " B";
        if (bytes >= 1_000) {
            unit = 1_000;
            sufix = " KB";
        }
        if (bytes >= 1_000_000) {
            unit = 1_000_000;
            sufix = " MB";
        }
        if (bytes >= 1_000_000_000) {
            unit = 1_000_000_000;
            sufix = " GB";
        }
        if (unit == 1) {
            return bytes + sufix;
        }
        return String.format("%.3f", bytes / ((double) unit)) + sufix;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: <port> <relayedAddress> <relayedPort>");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            System.out.println("Invalid port: " + ex.getMessage());
            System.out.println("Usage: <port> <relayedAddress> <relayedPort>");
            return;
        }

        InetAddress relayedInetAddress;
        try {
            relayedInetAddress = InetAddress.getByName(args[1]);
        } catch (UnknownHostException ex) {
            System.out.println("Invalid relayed address: " + ex.getMessage());
            System.out.println("Usage: <port> <relayedAddress> <relayedPort>");
            return;
        }

        int relayedPort;
        try {
            relayedPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            System.out.println("Invalid relayed port: " + ex.getMessage());
            System.out.println("Usage: <port> <relayedAddress> <relayedPort>");
            return;
        }

        SocketAddress relayedAddress;
        try {
            relayedAddress = new InetSocketAddress(relayedInetAddress, relayedPort);
        } catch (IllegalArgumentException ex) {
            System.out.println("Invalid relayed address: " + ex.getMessage());
            System.out.println("Usage: <port> <relayedAddress> <relayedPort>");
            return;
        }

        ServerSocket server;
        try {
            server = new ServerSocket(port);
        } catch (IOException ex) {
            System.out.println("Failed to create socket: " + ex.getMessage());
            return;
        }

        while (true) {
            final Socket client = server.accept();
            client.setSoTimeout(60000);
            System.out.println("[" + client.getRemoteSocketAddress() + " -> ???] Connection from client accepted.");
            POOL.submit(() -> {
                final Socket relayed = new Socket();
                try {
                    relayed.connect(relayedAddress);
                    relayed.setSoTimeout(60000);
                } catch (IOException | IllegalArgumentException ex) {
                    System.out.println("[" + client.getRemoteSocketAddress() + " -> XXX] Warning: Relayed server not responding, connection refused.");
                    
                    try {
                        client.close();
                    } catch (IOException ex1) {
                        System.out.println("[" + client.getRemoteSocketAddress() + " -> XXX] Warning: Could not close client connection after relayed server did not respond.");
                        throw new RuntimeException(ex1);
                    }
                    return;
                }
                System.out.println("[" + client.getRemoteSocketAddress() + " -> " + relayed.getRemoteSocketAddress() + "] Connection from relayed server accepted, relaying...");

                AtomicBoolean run = new AtomicBoolean(true);

                AtomicLong sent = new AtomicLong(0);
                AtomicLong received = new AtomicLong(0);

                Future<?> inToOut = POOL.submit(() -> {
                    try {
                        final InputStream clientIn = client.getInputStream();
                        final OutputStream relayedOut = relayed.getOutputStream();

                        byte[] buffer = new byte[4096];
                        while (run.get()) {
                            int read = clientIn.read(buffer);
                            if (read == -1) {
                                throw new IOException("End of stream.");
                            }
                            sent.addAndGet(read);
                            relayedOut.write(buffer, 0, read);
                        }
                    } catch (IOException ex) {
                        run.set(false);
                        throw new RuntimeException(ex);
                    }
                });

                Future<?> outToIn = POOL.submit(() -> {
                    try {
                        final OutputStream clientOut = client.getOutputStream();
                        final InputStream relayedIn = relayed.getInputStream();

                        byte[] buffer = new byte[4096];
                        while (run.get()) {
                            int read = relayedIn.read(buffer);
                            if (read == -1) {
                                throw new IOException("End of stream.");
                            }
                            received.addAndGet(read);
                            clientOut.write(buffer, 0, read);
                        }
                    } catch (IOException ex) {
                        run.set(false);
                        throw new RuntimeException(ex);
                    }
                });
                
                while (run.get()) {
                    System.out.println("[" + client.getRemoteSocketAddress() + " -> " + relayed.getRemoteSocketAddress() + "] Sent (to relayed): " + format(sent.get()) + "; Received (from relayed): " + format(received.get())+".");

                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException ex) {
                        System.out.println("[" + client.getRemoteSocketAddress() + " -> " + relayed.getRemoteSocketAddress() + "] Warning: Connection thread interrupted.");
                        throw new RuntimeException(ex);
                    }
                }
                
                try {
                    inToOut.get();
                    outToIn.get();
                } catch (InterruptedException | ExecutionException ex) {
                    try {
                        client.close();
                    } catch (IOException ex1) {}
                    try {
                        relayed.close();
                    } catch (IOException ex1) {}
                    System.out.println("[" + client.getRemoteSocketAddress() + " -> " + relayed.getRemoteSocketAddress() + "] Connection closed.");
                }
            });
        }
    }

}
