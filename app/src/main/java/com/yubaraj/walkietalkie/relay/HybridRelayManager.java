package com.yubaraj.walkietalkie.relay;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wi-Fi-first relay with Bluetooth Classic fallback. The application provides connection
 * factories because peer discovery, addresses and pairing are app/device-specific.
 * Listener callbacks run on the relay worker thread and must not block it.
 */
public final class HybridRelayManager implements AutoCloseable {
    public interface Factory { RelayTransport connect() throws IOException; }
    public interface Listener {
        void onTransportChanged(String transport);
        void onFrame(RelayFrame frame);
        void onError(String message);
    }

    private static final long NO_LINK_RETRY_MS = 2500L;
    private static final long RECONNECT_DELAY_MS = 1000L;
    private static final int MAX_REMEMBERED_PACKETS = 2048;

    private final Factory wifiFactory;
    private final Factory bluetoothFactory;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Inbound de-duplication and forwarding de-duplication are deliberately separate:
    // a newly received frame must still be eligible for its first forward operation.
    private final Map<String, Boolean> received = packetCache();
    private final Map<String, Boolean> forwarded = packetCache();
    private final Object packetLock = new Object();
    private final Object sendLock = new Object();
    private volatile RelayTransport active;

    public HybridRelayManager(Factory wifi, Factory bluetooth, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is required");
        this.wifiFactory = wifi;
        this.bluetoothFactory = bluetooth;
        this.listener = listener;
    }

    private static Map<String, Boolean> packetCache() {
        return new LinkedHashMap<String, Boolean>(512, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) {
                return size() > MAX_REMEMBERED_PACKETS;
            }
        };
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("Relay manager is already closed");
        if (running.compareAndSet(false, true)) io.execute(this::connectionLoop);
    }

    private void connectionLoop() {
        while (running.get()) {
            RelayTransport link = connectPreferred();
            if (link == null) {
                notifyTransport("DISCONNECTED");
                sleep(NO_LINK_RETRY_MS);
                continue;
            }

            active = link;
            notifyTransport(safeName(link));
            try {
                while (running.get()) {
                    RelayFrame frame = link.receive();
                    if (frame == null) throw new IOException("Relay peer disconnected");
                    if (frame.packetId == null || frame.packetId.trim().isEmpty()) {
                        throw new IOException("Received relay frame without packet ID");
                    }
                    synchronized (packetLock) {
                        if (received.put(frame.packetId, Boolean.TRUE) != null) continue;
                    }
                    try {
                        listener.onFrame(frame);
                    } catch (RuntimeException callbackError) {
                        notifyError("Frame callback failed: " + messageOf(callbackError));
                    }
                }
            } catch (IOException e) {
                if (running.get()) notifyError(messageOf(e));
            } catch (RuntimeException e) {
                if (running.get()) notifyError(messageOf(e));
            } finally {
                active = null;
                closeQuietly(link);
                notifyTransport("DISCONNECTED");
            }
            if (running.get()) sleep(RECONNECT_DELAY_MS);
        }
    }

    private RelayTransport connectPreferred() {
        RelayTransport link = tryConnect(wifiFactory);
        if (link != null) return link;
        return tryConnect(bluetoothFactory);
    }

    private RelayTransport tryConnect(Factory factory) {
        if (!running.get() || factory == null) return null;
        try {
            RelayTransport link = factory.connect();
            if (link == null) return null;
            if (!running.get()) {
                closeQuietly(link);
                return null;
            }
            return link;
        } catch (Exception e) {
            // A failed Wi-Fi attempt is expected when only Bluetooth is reachable.
            return null;
        }
    }

    public void send(RelayFrame frame) throws IOException {
        if (frame == null) throw new IOException("Relay frame is null");
        RelayTransport link = active;
        if (!running.get() || link == null) throw new IOException("No relay link available");
        synchronized (sendLock) {
            if (link != active || !running.get()) throw new IOException("Relay link changed; retry sending");
            link.send(frame);
        }
    }

    /** Forwards a packet at most once through this manager; use a separate cache from inbound de-duplication. */
    public void forward(RelayFrame frame, RelayTransport downstream) throws IOException {
        if (frame == null) throw new IOException("Relay frame is null");
        if (downstream == null) throw new IOException("Downstream relay is unavailable");
        if (frame.packetId == null || frame.packetId.trim().isEmpty()) throw new IOException("Packet ID is missing");
        synchronized (packetLock) {
            if (forwarded.put(frame.packetId, Boolean.TRUE) != null) return;
        }
        try {
            downstream.send(frame);
        } catch (IOException e) {
            synchronized (packetLock) { forwarded.remove(frame.packetId); }
            throw e;
        }
    }

    public String activeTransport() {
        RelayTransport link = active;
        return link == null ? "DISCONNECTED" : safeName(link);
    }

    private void notifyTransport(String transport) {
        try { listener.onTransportChanged(transport); }
        catch (RuntimeException e) { notifyError("Transport callback failed: " + messageOf(e)); }
    }

    private void notifyError(String message) {
        try { listener.onError(message); } catch (RuntimeException ignored) { }
    }

    private static String safeName(RelayTransport link) {
        try { return link.name() == null ? "UNKNOWN" : link.name(); }
        catch (RuntimeException e) { return "UNKNOWN"; }
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(RelayTransport link) {
        if (link != null) try { link.close(); } catch (Exception ignored) { }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        RelayTransport link = active;
        if (link != null) closeQuietly(link); // Closing unblocks a blocking receive().
        io.shutdownNow();
        try { io.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        active = null;
    }
}
