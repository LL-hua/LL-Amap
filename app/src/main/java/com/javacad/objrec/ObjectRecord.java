package com.javacad.objrec;

import com.javacad.bitstream.BitReader;

public class ObjectRecord {
    public static class ObjectRef {
        public long handle;
        public int offset;
        public ObjectRef(long handle, int offset) { this.handle = handle; this.offset = offset; }
    }

    public int offset;
    public int size;
    public byte[] body;
    public int bodyBitOffset;
    public boolean r2010Plus;
    public int handleStreamSizeBits;
    public int handleSizeFieldBits;

    public BitReader bodyBitStream() {
        BitReader r = new BitReader(body, 0);
        r.bitOffset = bodyBitOffset;
        return r;
    }

    public static class ObjHeader {
        public ObjectRecord rec;
        public int typeCode;
    }

    public static ObjectRecord parseObjectRecord(byte[] data, ObjectRef ref, boolean r2010) throws Exception {
        BitReader r = new BitReader(data, ref.offset);
        int sz = r.readMS();
        if (sz <= 0) throw new Exception("Size illegal: " + sz);

        ObjectRecord rec = new ObjectRecord();
        rec.offset = ref.offset;
        rec.size = sz;
        rec.bodyBitOffset = r.bitOffset;
        rec.r2010Plus = r2010;

        int bodyStart = r.byteOffset;
        long bodyBits = (long) sz * 8;

        if (r2010) {
            long fieldStartBit = (long) r.byteOffset * 8 + r.bitOffset;
            long hss = r.readUMC();
            rec.handleStreamSizeBits = (int) hss;
            long fieldEndBit = (long) r.byteOffset * 8 + r.bitOffset;
            rec.handleSizeFieldBits = (int) (fieldEndBit - fieldStartBit);
            bodyBits += rec.handleSizeFieldBits;
        }

        int bodyEnd = bodyStart + (int) ((bodyBits + 7) / 8);
        rec.body = new byte[bodyEnd - bodyStart];
        System.arraycopy(data, bodyStart, rec.body, 0, rec.body.length);
        return rec;
    }

    public static ObjHeader parseObjHeader(ObjectRecord rec) throws Exception {
        ObjHeader h = new ObjHeader();
        h.rec = rec;
        BitReader r = rec.bodyBitStream();
        if (rec.r2010Plus) {
            r.readUMC();
            h.typeCode = r.readOT();
        } else {
            h.typeCode = r.readBS();
        }
        return h;
    }
}
