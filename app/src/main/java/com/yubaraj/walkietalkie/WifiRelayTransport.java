package com.yubaraj.walkietalkie.relay;

import java.io.*;
import java.net.*;

/** TCP transport for an already reachable Wi-Fi/hotspot peer. */
public final class WifiRelayTransport implements RelayTransport {
    private final Socket socket; private final DataInputStream in; private final DataOutputStream out;
    public WifiRelayTransport(String host, int port, int timeoutMs) throws IOException {
        socket = new Socket(); socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }
    public String name() { return "WIFI"; }
    public synchronized void send(RelayFrame f) throws IOException { f.writeTo(out); }
    public RelayFrame receive() throws IOException { return RelayFrame.readFrom(in); }
    public void close() throws IOException { socket.close(); }
}
