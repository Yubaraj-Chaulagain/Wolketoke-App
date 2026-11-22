package com.yubaraj.walkietalkie.relay;

import java.io.Closeable;
import java.io.IOException;

/** One connected upstream/downstream relay link. Perform blocking calls on a worker thread. */
public interface RelayTransport extends Closeable {
    /** Stable transport label, for example WIFI or BLUETOOTH. */
    String name();

    /** Writes one complete length-prefixed frame. */
    void send(RelayFrame frame) throws IOException;

    /** Blocks until one complete frame arrives or the link fails/closes. */
    RelayFrame receive() throws IOException;
}
