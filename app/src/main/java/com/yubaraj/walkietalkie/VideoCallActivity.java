package com.yubaraj.walkietalkie;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import android.util.Size;

/**
 * Local Hotspot/Wi-Fi video call.
 * Video uses a direct TCP connection on port 45456 and H.264 hardware codec.
 * Audio remains on Wolketoke's existing offline voice channel.
 * Designed for a 1-to-1 call (Host <-> one caller).
 */
public class VideoCallActivity extends Activity {
    static final int VIDEO_PORT = 45456;
    static final int REQ_CAMERA = 701;
    static final int MAX_PACKET = 2 * 1024 * 1024;

    SurfaceView remoteView, localView;
    TextView status;
    Button endBtn, flipBtn;
    SurfaceHolder remoteHolder, localHolder;

    CameraDevice camera;
    CameraCaptureSession cameraSession;
    MediaCodec encoder, decoder;
    Surface encoderSurface;
    Thread networkThread, encodeThread;
    volatile boolean running = true, host = false;
    Socket socket;
    ServerSocket serverSocket;
    DataInputStream in;
    DataOutputStream out;
    final Object outLock = new Object();
    volatile byte[] codecConfig;
    String hostIp = "";
    boolean startedAudioHere = false;
    String cameraId;
    boolean frontCamera = false;
    // Prevent duplicate Camera2 open requests when surfaceCreated and the
    // immediate surface-validity check happen close together.
    boolean cameraOpening = false;
    volatile boolean remoteSurfaceReady = false;
    volatile byte[] pendingRemoteCodecConfig;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        host = getIntent().getBooleanExtra("host", false);
        hostIp = getIntent().getStringExtra("hostIp");
        if (hostIp == null) hostIp = "";

        getWindow().setStatusBarColor(0xFF050E1A);
        getWindow().setNavigationBarColor(0xFF050E1A);
        buildUi();
        remoteHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                remoteSurfaceReady = h.getSurface() != null && h.getSurface().isValid();
                byte[] cfg = pendingRemoteCodecConfig;
                if (remoteSurfaceReady && cfg != null) decode(cfg, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) {
                remoteSurfaceReady = h.getSurface() != null && h.getSurface().isValid();
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                remoteSurfaceReady = false;
                releaseDecoder();
            }
        });

        if (MainActivity.instance != null && !MainActivity.instance.handsFree) {
            MainActivity.instance.toggleHandsFree();
            startedAudioHere = true;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startVideo();
        }
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050E1A);

        remoteView = new SurfaceView(this);
        remoteHolder = remoteView.getHolder();
        root.addView(remoteView, new FrameLayout.LayoutParams(-1, -1));

        localView = new SurfaceView(this);
        localHolder = localView.getHolder();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(300, 420, Gravity.RIGHT | Gravity.TOP);
        lp.setMargins(0, 24, 18, 0);
        root.addView(localView, lp);

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(15);
        status.setPadding(18, 12, 18, 12);
        status.setBackgroundColor(0x99000000);
        status.setText(host ? "Starting local video host…" : "Connecting to local video host…");
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.TOP);
        sp.setMargins(12, 12, 12, 0);
        root.addView(status, sp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(12, 8, 12, 8);
        controls.setBackgroundColor(0x99000000);

        flipBtn = new Button(this);
        flipBtn.setText("🔄 CAMERA");
        flipBtn.setOnClickListener(v -> switchCamera());
        controls.addView(flipBtn, new LinearLayout.LayoutParams(0, -2, 1));

        endBtn = new Button(this);
        endBtn.setText("END VIDEO");
        endBtn.setOnClickListener(v -> finish());
        controls.addView(endBtn, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, cp);
        setContentView(root);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == REQ_CAMERA && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startVideo();
        else {
            setStatus("Camera permission is required for video.");
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200);
        }
    }

    void startVideo() {
        if (!running) return;
        if (host) {
            new Thread(this::acceptVideoClient, "video-host").start();
        } else {
            new Thread(this::connectVideoClient, "video-connect").start();
        }
        startCameraWhenReady();
    }

    void startCameraWhenReady() {
        localHolder.addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { openCamera(); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int he) {}
            public void surfaceDestroyed(SurfaceHolder h) { closeCamera(); }
        });
        if (localHolder.getSurface() != null && localHolder.getSurface().isValid()) openCamera();
    }

    void openCamera() {
        if (!running || camera != null || cameraOpening) return;
        if (localHolder == null || localHolder.getSurface() == null || !localHolder.getSurface().isValid()) return;
        cameraOpening = true;
        try {
            CameraManager cm = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if ((frontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) ||
                    (!frontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    cameraId = id; break;
                }
            }
            if (cameraId == null) {
                String[] ids = cm.getCameraIdList();
                if (ids.length == 0) throw new IllegalStateException("No camera is available on this device");
                cameraId = ids[0];
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraOpening = false;
                return;
            }

            prepareEncoder();
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice c) {
                    cameraOpening = false;
                    if (!running) { c.close(); return; }
                    camera = c;
                    createCameraSession();
                }
                public void onDisconnected(CameraDevice c) {
                    cameraOpening = false;
                    c.close();
                    if (camera == c) camera = null;
                    setStatus("Camera disconnected by Android.");
                }
                public void onError(CameraDevice c, int e) {
                    cameraOpening = false;
                    c.close();
                    if (camera == c) camera = null;
                    setStatus("Camera error: " + e);
                    releaseEncoder();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            cameraOpening = false;
            releaseEncoder();
            setStatus("Camera start failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    void prepareEncoder() throws Exception {
        encoder = MediaCodec.createEncoderByType("video/avc");
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", 640, 480);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 900_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
        encodeThread = new Thread(this::encodeLoop, "h264-encoder");
        encodeThread.start();
    }

    void createCameraSession() {
        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(encoderSurface);
            if (localHolder.getSurface() != null && localHolder.getSurface().isValid()) {
                localHolder.getSurface().setFrameRate(15f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                surfaces.add(localHolder.getSurface());
            }
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(encoderSurface);
            if (localHolder.getSurface() != null && localHolder.getSurface().isValid()) b.addTarget(localHolder.getSurface());
            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    if (!running || camera == null) { s.close(); return; }
                    cameraSession = s;
                    try {
                        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        s.setRepeatingRequest(b.build(), null, null);
                        setStatus(host ? "Local video host ready. Waiting for another phone…" : "Local video connected.");
                    } catch (Exception e) { setStatus("Camera session failed."); }
                }
                public void onConfigureFailed(CameraCaptureSession s) {
                    s.close();
                    setStatus("This phone cannot start preview and video encoder together. Try another camera mode/device.");
                    releaseEncoder();
                }
            }, null);
        } catch (Exception e) { setStatus("Video camera failed: " + e.getMessage()); }
    }

    void acceptVideoClient() {
        try {
            serverSocket = new ServerSocket(VIDEO_PORT);
            socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            setStatus("Video call connected. Sending/receiving video…");
            sendCachedCodecConfig();
            startDecoderReader();
        } catch (Exception e) {
            if (running) setStatus("Video host error: " + e.getMessage());
        }
    }

    void connectVideoClient() {
        try {
            if (hostIp.isEmpty()) throw new IOException("Host IP not found");
            IOException last = null;
            for (int attempt=0; attempt<15 && running; attempt++) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(hostIp, VIDEO_PORT), 2500);
                    last = null; break;
                } catch (IOException e) {
                    last = e;
                    try { if(socket!=null) socket.close(); } catch(Exception ignored) {}
                    Thread.sleep(700);
                }
            }
            if (last != null || socket == null || !socket.isConnected()) throw new IOException("Host video listener did not start");
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            setStatus("Video call connected. Sending/receiving video…");
            sendCachedCodecConfig();
            startDecoderReader();
        } catch (Exception e) {
            if (running) setStatus("Video connect failed: " + (e.getMessage() == null ? "check same Wi-Fi/Hotspot" : e.getMessage()));
        }
    }

    void encodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running && encoder != null) {
                int idx = encoder.dequeueOutputBuffer(info, 10000);
                if (idx >= 0) {
                    ByteBuffer bb = encoder.getOutputBuffer(idx);
                    if (bb != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        bb.position(info.offset);
                        bb.limit(info.offset + info.size);
                        bb.get(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            codecConfig = data;
                            sendVideoPacket(data, info.flags);
                        } else if (out != null) {
                            sendVideoPacket(data, info.flags);
                        }
                    }
                    encoder.releaseOutputBuffer(idx, false);
                }
            }
        } catch (Exception e) {
            if (running) setStatus("Video sending stopped.");
        }
    }

    void sendCachedCodecConfig() {
        byte[] cfg = codecConfig;
        if (cfg != null && cfg.length > 0) sendVideoPacket(cfg, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    void sendVideoPacket(byte[] data, int flags) {
        DataOutputStream o = out;
        if (o == null || socket == null || socket.isClosed()) return;
        try {
            synchronized(outLock) {
                o.writeInt(data.length);
                o.writeInt(flags);
                o.write(data);
                o.flush();
            }
        } catch (Exception ignored) {}
    }

    void startDecoderReader() {
        networkThread = new Thread(() -> {
            try {
                while (running && in != null) {
                    int len = in.readInt();
                    int flags = in.readInt();
                    if (len <= 0 || len > MAX_PACKET) break;
                    byte[] data = new byte[len];
                    in.readFully(data);
                    decode(data, flags);
                }
            } catch (Exception e) {
                if (running) setStatus("Remote video disconnected.");
            }
        }, "video-reader");
        networkThread.start();
    }

    void decode(byte[] data, int flags) {
        try {
            if (!remoteSurfaceReady || remoteHolder.getSurface() == null || !remoteHolder.getSurface().isValid()) {
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) pendingRemoteCodecConfig = data.clone();
                return;
            }
            if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) pendingRemoteCodecConfig = data.clone();
            if (decoder == null) {
                decoder = MediaCodec.createDecoderByType("video/avc");
                MediaFormat f = MediaFormat.createVideoFormat("video/avc", 640, 480);
                decoder.configure(f, remoteHolder.getSurface(), null, 0);
                decoder.start();
            }
            int idx = decoder.dequeueInputBuffer(10000);
            if (idx >= 0) {
                ByteBuffer b = decoder.getInputBuffer(idx);
                if (b != null) {
                    b.clear();
                    b.put(data);
                    decoder.queueInputBuffer(idx, 0, data.length, System.nanoTime()/1000, flags);
                }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx;
            while ((outIdx = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                decoder.releaseOutputBuffer(outIdx, true);
            }
        } catch (Exception e) {
            setStatus("Remote video decode error.");
        }
    }

    void switchCamera() {
        frontCamera = !frontCamera;
        cameraId = null;
        closeCamera();
        new Handler(Looper.getMainLooper()).postDelayed(this::openCamera, 250);
    }

    void setStatus(String s) {
        runOnUiThread(() -> { if (status != null) status.setText(s); });
    }

    void releaseEncoder() {
        MediaCodec old = encoder;
        encoder = null;
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) {}
            try { old.release(); } catch (Exception ignored) {}
        }
        Surface oldSurface = encoderSurface;
        encoderSurface = null;
        if (oldSurface != null) { try { oldSurface.release(); } catch (Exception ignored) {} }
    }

    void releaseDecoder() {
        MediaCodec old = decoder;
        decoder = null;
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) {}
            try { old.release(); } catch (Exception ignored) {}
        }
    }

    void closeCamera() {
        try { if (cameraSession != null) cameraSession.stopRepeating(); } catch (Exception ignored) {}
        try { if (cameraSession != null) cameraSession.close(); } catch (Exception ignored) {}
        cameraSession = null;
        CameraDevice oldCamera = camera;
        camera = null;
        try { if (oldCamera != null) oldCamera.close(); } catch (Exception ignored) {}
        releaseEncoder();
    }

    @Override protected void onDestroy() {
        running = false;
        closeCamera();
        releaseDecoder();
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (startedAudioHere && MainActivity.instance != null && MainActivity.instance.handsFree) {
            MainActivity.instance.toggleHandsFree();
        }
        super.onDestroy();
    }
}
