package com.iiordanov.bVNC.h264;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class H264AccessUnitSplitterTest {
    private static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80};
    // first_mb_in_slice == 0 (leading 1 bit)
    private static final byte[] IDR = {0, 0, 0, 1, 0x65, (byte) 0x88, 0x01, 0x02};
    private static final byte[] P1 = {0, 0, 0, 1, 0x41, (byte) 0x9a, 0x03};
    private static final byte[] P2 = {0, 0, 1, 0x41, (byte) 0x9a, 0x04};
    // Second slice of the same picture: first_mb_in_slice != 0 (leading 0 bit)
    private static final byte[] P2_SLICE2 = {0, 0, 1, 0x41, 0x40, 0x05};

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    @Test
    public void singleKeyFrameIsOneAccessUnit() {
        byte[] stream = concat(SPS, PPS, IDR);
        List<byte[]> units = H264AccessUnitSplitter.split(stream, 0, stream.length);
        assertEquals(1, units.size());
        assertArrayEquals(stream, units.get(0));
        assertTrue(H264AccessUnitSplitter.containsNalType(units.get(0), H264AccessUnitSplitter.NAL_IDR));
    }

    @Test
    public void concatenatedFramesAreSplit() {
        byte[] stream = concat(SPS, PPS, IDR, P1, P2, P2_SLICE2);
        List<byte[]> units = H264AccessUnitSplitter.split(stream, 0, stream.length);
        assertEquals(3, units.size());
        assertArrayEquals(concat(SPS, PPS, IDR), units.get(0));
        assertArrayEquals(P1, units.get(1));
        assertArrayEquals(concat(P2, P2_SLICE2), units.get(2));
        assertFalse(H264AccessUnitSplitter.containsNalType(units.get(1), H264AccessUnitSplitter.NAL_IDR));
    }

    @Test
    public void parameterSetsStartNewAccessUnit() {
        byte[] stream = concat(P1, SPS, PPS, IDR);
        List<byte[]> units = H264AccessUnitSplitter.split(stream, 0, stream.length);
        assertEquals(2, units.size());
        assertArrayEquals(P1, units.get(0));
        assertArrayEquals(concat(SPS, PPS, IDR), units.get(1));
    }

    @Test
    public void respectsOffsetAndLength() {
        byte[] frames = concat(IDR, P1);
        byte[] stream = concat(new byte[]{9, 9}, frames, new byte[]{7, 7, 7});
        List<byte[]> units = H264AccessUnitSplitter.split(stream, 2, frames.length);
        assertEquals(2, units.size());
        assertArrayEquals(IDR, units.get(0));
        assertArrayEquals(P1, units.get(1));
    }

    @Test
    public void extractsParameterSets() {
        byte[] keyFrame = concat(SPS, PPS, IDR);
        assertArrayEquals(SPS, H264AccessUnitSplitter.extractNalUnit(keyFrame, H264AccessUnitSplitter.NAL_SPS));
        assertArrayEquals(PPS, H264AccessUnitSplitter.extractNalUnit(keyFrame, H264AccessUnitSplitter.NAL_PPS));
        assertNull(H264AccessUnitSplitter.extractNalUnit(P1, H264AccessUnitSplitter.NAL_SPS));
    }

    @Test
    public void dataWithoutStartCodeIsPassedThrough() {
        byte[] stream = {1, 2, 3};
        List<byte[]> units = H264AccessUnitSplitter.split(stream, 0, stream.length);
        assertEquals(1, units.size());
        assertArrayEquals(stream, units.get(0));
        assertTrue(H264AccessUnitSplitter.split(new byte[0], 0, 0).isEmpty());
    }
}
