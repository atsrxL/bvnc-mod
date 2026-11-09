/**
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.iiordanov.bVNC.h264;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decodes an Open H.264 (RFB encoding 50) stream with MediaCodec directly onto a Surface.
 * <p>
 * The RFB thread only splits and enqueues access units, so it never waits for the decoder.
 * This matters because servers such as PiKVM queue frames until the next update request and
 * drop frames when the client falls behind, which breaks the reference chain. A feeder thread
 * queues input and an output thread renders decoded frames.
 * <p>
 * Until a surface is available, access units starting at the last IDR frame are kept so that
 * decoding can begin as soon as the view is ready. On API 26+, the decoder is parked on an
 * offscreen surface when the view surface goes away, so decoding state survives the app being
 * backgrounded without having to wait for the next key frame, which some servers send rarely.
 */
public class H264VideoDecoder {
    private static final String TAG = "H264VideoDecoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long TIMEOUT_US = 50_000;
    private static final long FRAME_DURATION_US = 16_666;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
    private static final int MIN_INPUT_BUFFER_SIZE = 1024 * 1024;
    private static final int BACKLOG_WARNING_UNITS = 30;
    private static final long STATS_INTERVAL_MS = 10_000;

    public interface FrameListener {
        void onFrameRendered();
    }

    private final Object lock = new Object();
    private final FrameListener listener;
    // Access units kept while no decoder is running, starting with an IDR frame.
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private int pendingBytes;
    private Session session;
    private Surface surface;
    private Surface parkingSurface;
    private SurfaceTexture parkingTexture;
    private boolean waitingForKeyFrame = true;
    private boolean released;
    private int width;
    private int height;

    public H264VideoDecoder(FrameListener listener) {
        this.listener = listener;
    }

    /**
     * Feeds the payload of one Open H.264 rectangle. Called from the RFB thread; does not block
     * on the decoder.
     *
     * @param reset true if the server asked for the decoding context to be reset
     */
    public void decode(byte[] data, int length, boolean reset, int frameWidth, int frameHeight) {
        List<byte[]> accessUnits = H264AccessUnitSplitter.split(data, 0, length);
        synchronized (lock) {
            if (released) {
                return;
            }
            if (reset || frameWidth != width || frameHeight != height) {
                resetLocked("reset: " + reset + ", size: " + frameWidth + "x" + frameHeight);
                width = frameWidth;
                height = frameHeight;
            }
            if (session != null && session.failed) {
                resetLocked("decoder failed");
            }
            for (byte[] accessUnit : accessUnits) {
                handleAccessUnitLocked(accessUnit);
            }
        }
    }

    /**
     * Sets the surface to render to, or null when the current surface is going away.
     * The caller keeps ownership of the surface.
     */
    public void setSurface(Surface newSurface) {
        synchronized (lock) {
            if (released) {
                return;
            }
            surface = newSurface;
            if (session == null) {
                if (surface != null && !pending.isEmpty()) {
                    startSessionLocked();
                }
                return;
            }
            Surface target = surface != null ? surface : getParkingSurfaceLocked();
            if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    session.codec.setOutputSurface(target);
                    session.render = surface != null;
                    return;
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not switch output surface: " + e);
                }
            }
            resetLocked("surface changed");
        }
    }

    public void release() {
        synchronized (lock) {
            released = true;
            resetLocked("released");
            if (parkingSurface != null) {
                parkingSurface.release();
                parkingSurface = null;
            }
            if (parkingTexture != null) {
                parkingTexture.release();
                parkingTexture = null;
            }
        }
    }

    private Surface getParkingSurfaceLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        if (parkingSurface == null) {
            parkingTexture = new SurfaceTexture(false);
            parkingSurface = new Surface(parkingTexture);
        }
        return parkingSurface;
    }

    private void resetLocked(String reason) {
        if (session != null || !pending.isEmpty()) {
            Log.i(TAG, "Resetting decoder (" + reason + "), waiting for next key frame");
        }
        if (session != null) {
            session.stop();
            session = null;
        }
        pending.clear();
        pendingBytes = 0;
        waitingForKeyFrame = true;
    }

    private void handleAccessUnitLocked(byte[] accessUnit) {
        boolean keyFrame = H264AccessUnitSplitter.containsNalType(accessUnit, H264AccessUnitSplitter.NAL_IDR);
        if (keyFrame) {
            waitingForKeyFrame = false;
            if (session == null) {
                pending.clear();
                pendingBytes = 0;
            }
        }
        if (waitingForKeyFrame) {
            return;
        }
        if (session != null) {
            session.enqueue(accessUnit);
            return;
        }
        pending.add(accessUnit);
        pendingBytes += accessUnit.length;
        if (pendingBytes > MAX_PENDING_BYTES) {
            resetLocked("too much data buffered without a surface");
            return;
        }
        if (surface != null) {
            startSessionLocked();
        }
    }

    private MediaFormat createFormat(boolean lowLatency) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        byte[] first = pending.peekFirst();
        byte[] sps = H264AccessUnitSplitter.extractNalUnit(first, H264AccessUnitSplitter.NAL_SPS);
        byte[] pps = H264AccessUnitSplitter.extractNalUnit(first, H264AccessUnitSplitter.NAL_PPS);
        if (sps != null && pps != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        }
        int maxInputSize = Math.max(width * height, MIN_INPUT_BUFFER_SIZE);
        for (byte[] accessUnit : pending) {
            maxInputSize = Math.max(maxInputSize, accessUnit.length);
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        return format;
    }

    private void startSessionLocked() {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(MIME);
            try {
                codec.configure(createFormat(true), surface, null, 0);
            } catch (RuntimeException e) {
                Log.w(TAG, "Low latency configuration rejected, retrying without it: " + e);
                codec.reset();
                codec.configure(createFormat(false), surface, null, 0);
            }
            codec.start();
            Log.i(TAG, "Started " + codec.getName() + " for " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "Could not start H.264 decoder", e);
            if (codec != null) {
                codec.release();
            }
            resetLocked("start failed");
            return;
        }
        session = new Session(codec);
        while (!pending.isEmpty()) {
            session.enqueue(pending.pollFirst());
        }
        pendingBytes = 0;
    }

    /**
     * One configured MediaCodec instance with its feeder and output threads.
     */
    private class Session {
        final MediaCodec codec;
        final LinkedBlockingQueue<byte[]> input = new LinkedBlockingQueue<>();
        final Thread feeder;
        final Thread output;
        volatile boolean running = true;
        volatile boolean failed = false;
        volatile boolean render = true;
        boolean backlogWarned = false;
        final AtomicInteger queuedFrames = new AtomicInteger();
        int renderedFrames;

        Session(MediaCodec codec) {
            this.codec = codec;
            feeder = new Thread(this::feed, "H264VideoDecoderInput");
            output = new Thread(this::drain, "H264VideoDecoderOutput");
            feeder.start();
            output.start();
        }

        void enqueue(byte[] accessUnit) {
            input.add(accessUnit);
            int backlog = input.size();
            if (backlog > BACKLOG_WARNING_UNITS && !backlogWarned) {
                Log.w(TAG, "Decoder is falling behind, " + backlog + " frames waiting");
                backlogWarned = true;
            } else if (backlog == 0) {
                backlogWarned = false;
            }
        }

        private void feed() {
            long presentationTimeUs = 0;
            try {
                while (running) {
                    byte[] accessUnit = input.poll(TIMEOUT_US, TimeUnit.MICROSECONDS);
                    if (accessUnit == null) {
                        continue;
                    }
                    int index = -1;
                    while (running && index < 0) {
                        index = codec.dequeueInputBuffer(TIMEOUT_US);
                    }
                    if (!running) {
                        return;
                    }
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    if (buffer == null || accessUnit.length > buffer.capacity()) {
                        Log.e(TAG, "Access unit of " + accessUnit.length + " bytes does not fit in input buffer");
                        codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0);
                        failed = true;
                        return;
                    }
                    buffer.clear();
                    buffer.put(accessUnit);
                    codec.queueInputBuffer(index, 0, accessUnit.length, presentationTimeUs, 0);
                    presentationTimeUs += FRAME_DURATION_US;
                    queuedFrames.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                if (running) {
                    Log.e(TAG, "Decoder input failed", e);
                    failed = true;
                }
            }
        }

        private void drain() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long statsStart = SystemClock.elapsedRealtime();
            try {
                while (running) {
                    int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (index >= 0) {
                        boolean show = render && info.size > 0;
                        codec.releaseOutputBuffer(index, show);
                        if (show) {
                            renderedFrames++;
                            listener.onFrameRendered();
                        }
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Output format: " + codec.getOutputFormat());
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - statsStart >= STATS_INTERVAL_MS) {
                        Log.i(TAG, String.format(java.util.Locale.ROOT,
                                "Rendered %.1f fps, queued %d, rendered %d, backlog %d",
                                renderedFrames * 1000.0 / (now - statsStart), queuedFrames.getAndSet(0),
                                renderedFrames, input.size()));
                        statsStart = now;
                        renderedFrames = 0;
                    }
                }
            } catch (RuntimeException e) {
                if (running) {
                    Log.e(TAG, "Decoder output failed", e);
                    failed = true;
                }
            }
        }

        void stop() {
            running = false;
            joinQuietly(feeder);
            joinQuietly(output);
            try {
                codec.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "Error stopping decoder: " + e);
            }
            codec.release();
            input.clear();
        }

        private void joinQuietly(Thread thread) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
