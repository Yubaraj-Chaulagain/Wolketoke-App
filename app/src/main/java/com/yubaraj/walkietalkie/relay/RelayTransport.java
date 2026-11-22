package com.yubaraj.walkietalkie.relay;

import java.io.Closeable;
import java.io.IOException;

/** A single upstream transport. Call from a worker thread, never the UI thread. */
public interface RelayTransport extends Closeable {
    String name();
    void send(RelayFrame frame) throws IOException;
    RelayFrame receive() throws IOException;
}
