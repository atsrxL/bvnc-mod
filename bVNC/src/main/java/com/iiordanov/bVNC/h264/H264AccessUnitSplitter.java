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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits an Annex-B H.264 byte stream into access units.
 * <p>
 * Servers such as PiKVM may concatenate several encoded frames into a single Open H.264
 * rectangle when the client falls behind. Android decoders expect one access unit per input
 * buffer, so the stream is split here before being queued.
 */
public final class H264AccessUnitSplitter {
    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;

    private H264AccessUnitSplitter() {
    }

    /**
     * Locates NAL units in an Annex-B stream.
     *
     * @return list of {startCodeOffset, payloadOffset, endOffset} triplets
     */
    static List<int[]> findNalUnits(byte[] data, int offset, int length) {
        List<int[]> units = new ArrayList<>();
        int end = offset + length;
        int previousStart = -1;
        int previousPayload = -1;
        int i = offset;
        while (i + 2 < end) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                int startCode = (i > offset && data[i - 1] == 0) ? i - 1 : i;
                if (previousStart >= 0) {
                    units.add(new int[]{previousStart, previousPayload, startCode});
                }
                previousStart = startCode;
                previousPayload = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        if (previousStart >= 0 && previousPayload < end) {
            units.add(new int[]{previousStart, previousPayload, end});
        }
        return units;
    }

    private static boolean isVcl(int type) {
        return type == NAL_SLICE || type == NAL_IDR;
    }

    private static boolean beginsAccessUnit(int type) {
        return type == NAL_AUD || type == NAL_SPS || type == NAL_PPS || type == NAL_SEI
                || (type >= 14 && type <= 18);
    }

    /**
     * Splits the stream into access units. Each returned array is an independent copy that
     * includes the Annex-B start codes. Data without any start code is returned unchanged.
     */
    public static List<byte[]> split(byte[] data, int offset, int length) {
        List<byte[]> accessUnits = new ArrayList<>();
        List<int[]> units = findNalUnits(data, offset, length);
        if (units.isEmpty()) {
            if (length > 0) {
                accessUnits.add(Arrays.copyOfRange(data, offset, offset + length));
            }
            return accessUnits;
        }

        int accessUnitStart = units.get(0)[0];
        boolean hasVcl = false;
        for (int[] unit : units) {
            int type = data[unit[1]] & 0x1f;
            boolean startsNew = false;
            if (hasVcl) {
                if (beginsAccessUnit(type)) {
                    startsNew = true;
                } else if (isVcl(type) && unit[1] + 1 < unit[2]) {
                    // first_mb_in_slice is ue(v) coded; a leading 1 bit means it is zero,
                    // which marks the first slice of a new picture.
                    startsNew = (data[unit[1] + 1] & 0x80) != 0;
                }
            }
            if (startsNew) {
                accessUnits.add(Arrays.copyOfRange(data, accessUnitStart, unit[0]));
                accessUnitStart = unit[0];
                hasVcl = false;
            }
            if (isVcl(type)) {
                hasVcl = true;
            }
        }
        accessUnits.add(Arrays.copyOfRange(data, accessUnitStart, units.get(units.size() - 1)[2]));
        return accessUnits;
    }

    public static boolean containsNalType(byte[] accessUnit, int nalType) {
        for (int[] unit : findNalUnits(accessUnit, 0, accessUnit.length)) {
            if ((accessUnit[unit[1]] & 0x1f) == nalType) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first NAL unit of the given type, including its start code, or null.
     */
    public static byte[] extractNalUnit(byte[] accessUnit, int nalType) {
        for (int[] unit : findNalUnits(accessUnit, 0, accessUnit.length)) {
            if ((accessUnit[unit[1]] & 0x1f) == nalType) {
                return Arrays.copyOfRange(accessUnit, unit[0], unit[2]);
            }
        }
        return null;
    }
}
