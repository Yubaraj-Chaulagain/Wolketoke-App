package com.yubaraj.walkietalkie.relay;

import java.io.*;
import java.util.UUID;

/** Length-prefixed relay frame. Payload is raw PCM/audio or a control message. */
public final class RelayFrame {
    public static final int TYPE_AUDIO = 1, TYPE_CONTROL = 2;
    public final int type;
    public final String originId, packetId;
    public final byte[] payload;

    public RelayFrame(int type, String originId, String packetId, byte[] payload) {
        this.type = type; this.originId = originId; this.packetId = packetId; this.payload = payload;
    }
    public static RelayFrame audio(String originId, byte[] payload) {
        return new RelayFrame(TYPE_AUDIO, originId, UUID.randomUUID().toString(), payload);
    }
    public void writeTo(DataOutputStream out) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        body.writeInt(type); body.writeUTF(originId); body.writeUTF(packetId);
        body.writeInt(payload.length); body.write(payload); body.flush();
        byte[] frame = bytes.toByteArray(); out.writeInt(frame.length); out.write(frame); out.flush();
    }
    public static RelayFrame readFrom(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 12 || size > 1024 * 1024) throw new IOException("Invalid relay frame size");
        byte[] raw = new byte[size]; in.readFully(raw); DataInputStream body = new DataInputStream(new ByteArrayInputStream(raw));
        int type = body.readInt(); String origin = body.readUTF(), packet = body.readUTF(); int n = body.readInt();
        if (n < 0 || n > body.available()) throw new IOException("Invalid payload length");
        byte[] payload = new byte[n]; body.readFully(payload); return new RelayFrame(type, origin, packet, payload);
    }
}
