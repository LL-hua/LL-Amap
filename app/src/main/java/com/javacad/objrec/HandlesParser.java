package com.javacad.objrec;

import com.javacad.objrec.ObjectRecord.ObjectRef;
import java.util.ArrayList;
import java.util.List;

public class HandlesParser {
    public static List<ObjectRef> parseObjectMapHandles(byte[] data) {
        List<ObjectRef> objects = new ArrayList<>();
        int pos = 0;
        while (pos + 2 <= data.length) {
            int chunkSize = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (chunkSize == 2) break;
            if (chunkSize < 2 || pos + chunkSize - 2 > data.length) break;

            int chunkEnd = pos + chunkSize - 2;
            long lastHandle = 0, lastOffset = 0;
            
            while (pos < chunkEnd) {
                long prevHandle = lastHandle, prevOffset = lastOffset;
                try {
                    long dHandle = readUnsignedModularChar(data, new int[]{pos});
                    // 在这里由于Java引用传值限制，用临时变量处理pos
                    int[] p = {pos};
                    dHandle = readUnsignedModularChar(data, p);
                    pos = p[0];
                    
                    p[0] = pos;
                    long dOffset = readModularChar(data, p);
                    pos = p[0];

                    lastHandle += dHandle;
                    lastOffset += dOffset;
                    if (lastHandle < 0 || lastOffset < 0 || lastOffset > 0xFFFFFFFFL) {
                        lastHandle = prevHandle; lastOffset = prevOffset;
                        continue;
                    }
                    objects.add(new ObjectRef(lastHandle, (int) lastOffset));
                } catch (Exception e) {
                    break;
                }
            }
            pos = chunkEnd + 2; // 跳过 CRC
        }
        return objects;
    }

    public static long readUnsignedModularChar(byte[] data, int[] pos) {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            if (pos[0] >= data.length) return value;
            byte b = data[pos[0]++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            if (shift >= 28) break;
        }
        return value;
    }

    public static long readModularChar(byte[] data, int[] pos) {
        long value = 0;
        for (int shift = 0; shift < 28; shift += 7) {
            if (pos[0] >= data.length) return value;
            byte b = data[pos[0]++];
            if ((b & 0x80) == 0) {
                boolean negative = (b & 0x40) != 0;
                if (negative) b &= 0xBF;
                value |= (long) b << shift;
                return negative ? -value : value;
            }
            value |= (long) (b & 0x7F) << shift;
        }
        return value;
    }
}
