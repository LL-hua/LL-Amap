package com.javacad.bitstream;

public class BitReader {
    private final byte[] data;
    public int byteOffset;
    public int bitOffset;

    public BitReader(byte[] data) { this(data, 0); }
    public BitReader(byte[] data, int startByteOffset) {
        this.data = data;
        this.byteOffset = startByteOffset;
        this.bitOffset = 0;
    }

    public long tellBits() { return (long) byteOffset * 8 + bitOffset; }
    public void setBitPos(long bitPos) { byteOffset = (int) (bitPos / 8); bitOffset = (int) (bitPos % 8); }

    public long readBits(int numBits) {
        long result = 0;
        for (int i = 0; i < numBits; i++) {
            int bit = (data[byteOffset] >> (7 - bitOffset)) & 1;
            result = (result << 1) | bit;
            bitOffset++;
            if (bitOffset == 8) { bitOffset = 0; byteOffset++; }
        }
        return result;
    }

    public int readB() { return (int) readBits(1); }
    public int readBB() { return (int) readBits(2); }
        public int readRC() {
        if (bitOffset == 0) return data[byteOffset++] & 0xFF;
        int v = ((data[byteOffset] & 0xFF) << bitOffset) | ((data[byteOffset + 1] & 0xFF) >>> (8 - bitOffset));
        byteOffset++;
        return v & 0xFF;
    }

    public int readRS() {
        int lo = readRC(), hi = readRC();
        return (hi << 8) | lo;
    }

    public long readRL() {
        int lo = readRS(), hi = readRS();
        return ((long) hi << 16) | lo;
    }

    public int readBS() {
        int p = readBB();
        if (p == 0) return readRS();
        if (p == 1) return readRC();
        if (p == 3) return 256;
        return 0;
    }

    public long readBL() {
        int p = readBB();
        if (p == 0) return readRL();
        if (p == 1) return readRC();
        if (p == 3) return 256;
        return 0;
    }

    public long readBLL() {
        int len = (int) readBits(3);
        long v = 0;
        for (int i = 0; i < len; i++) v |= ((long) readRC() << (8 * i));
        return v;
    }

    public double readRD() {
        long bits = 0;
        for (int i = 0; i < 8; i++) bits |= ((long) readRC() << (i * 8));
        return Double.longBitsToDouble(bits);
    }

    public int readMS() {
        int first = readRS();
        long value = first & 0x7FFF;
        if ((first & 0x8000) == 0) return (int) value;
        return (int) (value | ((long)(readRS() & 0x7FFF) << 15));
    }

    public long readUMC() {
        long value = 0; int shift = 0;
        while (true) {
            int b = readRC();
            value |= (long)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            if ((shift += 7) >= 28) break;
        }
        return value;
    }

    public int readOT() {
        int op = readBB();
        if (op == 0) return readRC();
        if (op == 1) return readRC() + 0x01F0;
        return readRS();
    }

    public double readBD() {
        int p = readBB();
        if (p == 0) return readRD();
        if (p == 1) return 1.0;
        return 0.0;
    }

    public double readDD(double def) {
        int p = readBB();
        if (p == 0) return def;
        if (p == 3) return readRD();
        long bits = Double.doubleToLongBits(def);
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte)(bits >> (i * 8));
        if (p == 1) { for (int i = 0; i < 4; i++) b[i] = (byte) readRC(); }
        else if (p == 2) {
            for (int i = 4; i < 6; i++) b[i] = (byte) readRC();
            for (int i = 0; i < 4; i++) b[i] = (byte) readRC();
        }
        long val = 0;
        for (int i = 0; i < 8; i++) val |= ((long)(b[i] & 0xFF) << (i * 8));
        return Double.longBitsToDouble(val);
    }

    public double readBT() { return readB() == 1 ? 0.0 : readBD(); }
    public double[] read3BD() { return new double[] { readBD(), readBD(), readBD() }; }
    public double[] readBE() { return readB() == 1 ? new double[]{0,0,1} : read3BD(); }

    public int readH() {
        int counter = readRC() & 0x0F, value = 0;
        for (int i = 0; i < counter; i++) value = (value << 8) | readRC();
        return value;
    }

    public void readEntityColorHead() {
        if (readB() != 0) { readB(); return; }
        if (readB() == 1) { readRC(); return; }
        int flags = readRS();
        if ((flags & 0x2000) != 0) readBL();
        if ((flags & 0x4000) == 0 && (flags & 0x8000) != 0) readBL();
    }

    public String readTU() {
        int len = readBS();
        if (len <= 0 || len > 10000) return "";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int u = readRS();
            if (u != 0) sb.append((char) u);
        }
        return sb.toString();
    }

    public int readColorIndex() {
        if (readB() != 0) return readB() == 1 ? 256 : 7;
        if (readB() == 1) return readRC();
        int flags = readRS();
        if ((flags & 0x2000) != 0) readBL();
        if ((flags & 0x4000) == 0 && (flags & 0x8000) != 0) {
            long rgb = readBL();
            return (int)(rgb & 0xFFFFFF) | 0x1000000;
        }
        return flags & 0x1FF;
    }

    public long readHandleRef(long base) {
        int b = readRC();
        int code = (b >> 4) & 0x0F, cnt = b & 0x0F;
        long val = 0;
        for (int i = 0; i < cnt; i++) val = (val << 8) | readRC();
        if (code == 0x06) return base + 1;
        if (code == 0x08) return base > 0 ? base - 1 : 0;
        if (code == 0x0A) return base + val;
        if (code == 0x0C) return base >= val ? base - val : 0;
        return val;
    }
}