package com.yubaraj.walkietalkie.relay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Length-prefixed relay frame. Payload may contain audio bytes or a control message. */
public final class RelayFrame {
    public static final int TYPE_AUDIO = 1;
    public static final int TYPE_CONTROL = 2;
    /** Keep frames bounded so a malformed peer cannot allocate unbounded memory. */
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    public static final int MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - 128;

    public final int type;
    public final String originId;
    public final String packetId;
    public final byte[] payload;

    public RelayFrame(int type, String originId, String packetId, byte[] payload) {
        if (type != TYPE_AUDIO && type != TYPE_CONTROL) {
            throw new IllegalArgumentException("Unsupported relay frame type: " + type);
        }
        if (originId == null || originId.trim().isEmpty()) {
            throw new IllegalArgumentException("originId is required");
        }
        if (packetId == null || packetId.trim().isEmpty()) {
            throw new IllegalArgumentException("packetId is required");
        }
        if (payload == null) throw new IllegalArgumentException("payload is required");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Relay payload is too large");
        }
        this.type = type;
        this.originId = originId;
        this.packetId = packetId;
        this.payload = payload;
    }

    public static RelayFrame audio(String originId, byte[] payload) {
        return new RelayFrame(TYPE_AUDIO, originId, UUID.randomUUID().toString(), payload);
    }

    public static RelayFrame control(String originId, byte[] payload) {
        return new RelayFrame(TYPE_CONTROL, originId, UUID.randomUUID().toString(), payload);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        if (out == null) throw new IOException("Output stream is unavailable");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + payload.length);
        DataOutputStream body = new DataOutputStream(bytes);
        body.writeInt(type);
        body.writeUTF(originId);
        body.writeUTF(packetId);
        body.writeInt(payload.length);
        body.write(payload);
        body.flush();

        byte[] frame = bytes.toByteArray();
        if (frame.length > MAX_FRAME_BYTES) throw new IOException("Relay frame is too large");
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    public static RelayFrame readFrom(DataInputStream in) throws IOException {
        if (in == null) throw new IOException("Input stream is unavailable");
        final int size;
        try {
            size = in.readInt();
        } catch (java.io.EOFException e) {
            throw e;
        }
        if (size < 12 || size > MAX_FRAME_BYTES) {
            throw new IOException("Invalid relay frame size: " + size);
        }

        byte[] raw = new byte[size];
        in.readFully(raw);
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(raw));
        int type = body.readInt();
        if (type != TYPE_AUDIO && type != TYPE_CONTROL) {
            throw new IOException("Unsupported relay frame type: " + type);
        }
        String origin = body.readUTF();
        String packet = body.readUTF();
        int payloadLength = body.readInt();
        if (origin.trim().isEmpty() || packet.trim().isEmpty()) {
            throw new IOException("Missing relay frame identifier");
        }
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES || payloadLength != body.available()) {
            throw new IOException("Invalid relay payload length: " + payloadLength);
        }
        byte[] payload = new byte[payloadLength];
        body.readFully(payload);
        return new RelayFrame(type, origin, packet, payload);
    }
}
