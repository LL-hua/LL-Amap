package com.javacad.container;

public class Lz77 {
    
    private static class Stream {
        byte[] src;
        int at = 0;
        Stream(byte[] src) { this.src = src; }
        
        int take() throws Exception {
            if (at >= src.length) throw new Exception("压缩流意外结束");
            return src[at++] & 0xFF;
        }
        
        int[] splitWord() throws Exception {
            int b1 = take();
            int b2 = take();
            int offset = (b1 >> 2) | (b2 << 6);
            int litCount = b1 & 0x03;
            return new int[]{offset, litCount};
        }
        
        int extendedCount() throws Exception {
            int b = take();
            int total = 0;
            if (b == 0x00) {
                total = 0xFF;
                while (true) {
                    int next = take();
                    if (next != 0x00) {
                        b = next;
                        break;
                    }
                    total += 0xFF;
                }
            }
            return total + b;
        }
        
        int[] literalRun() throws Exception {
            int b = take();
            if (b >= 0x01 && b <= 0x0F) return new int[]{b + 3, 0};
            if ((b & 0xF0) != 0) return new int[]{0, b};
            if (b == 0x00) {
                int length = 0x0F;
                while (true) {
                    int next = take();
                    if (next != 0x00) return new int[]{length + next + 3, 0};
                    length += 0xFF;
                }
            }
            return new int[]{0, 0};
        }
    }

    private static class Form {
        int lo, hi, lenBase, opMask, offBase;
        boolean ext;
        Form(int lo, int hi, int lenBase, int opMask, boolean ext, int offBase) {
            this.lo = lo; this.hi = hi; this.lenBase = lenBase; this.opMask = opMask; this.ext = ext; this.offBase = offBase;
        }
    }

    private static final Form[] FORMS = {
        new Form(0x10, 0x10, 9, 0, true, 0x3FFF),
        new Form(0x12, 0x1F, 2, 0x0F, false, 0x3FFF),
        new Form(0x20, 0x20, 0x21, 0, true, 0),
        new Form(0x21, 0x3F, -0x1E, 0xFF, false, 0)
    };

    private static Form formOf(int op) {
        for (Form f : FORMS) {
            if (op >= f.lo && op <= f.hi) return f;
        }
        return null;
    }

    private static class Emitter {
        byte[] buf = new byte[1024];
        int len = 0;
        void ensure(int extra) {
            if (len + extra > buf.length) {
                int newCap = Math.max(buf.length * 2, len + extra);
                byte[] n = new byte[newCap];
                System.arraycopy(buf, 0, n, 0, len);
                buf = n;
            }
        }
        void append(byte b) { ensure(1); buf[len++] = b; }
        void append(byte[] b, int off, int n) { ensure(n); System.arraycopy(b, off, buf, len, n); len += n; }
        void appendZeros(int n) { ensure(n); len += n; }
        void appendWindow(int distance, int length) throws Exception {
            if (length == 0) return;
            ensure(length);
            if (distance > len) {
                len += length; // 零填充
                return;
            }
            int base = len - distance;
            for (int i = 0; i < length; i++) {
                buf[len++] = buf[base + i];
            }
        }
    }

    public static byte[] decompress(byte[] src, int dstSize) throws Exception {
        if (dstSize == 0) return new byte[0];
        Stream s = new Stream(src);
        Emitter e = new Emitter();
        
        int[] lr = s.literalRun();
        int litLen = lr[0];
        int nextOp = lr[1];
        
        if (litLen > 0) {
            if (s.at + litLen > src.length) throw new Exception("字面量游程超出压缩数据");
            e.append(src, s.at, litLen);
            s.at += litLen;
        }
        
        while (s.at < src.length) {
            if (nextOp == 0x00) {
                nextOp = s.take();
            }
            int op = nextOp;
            nextOp = 0x00;
            
            if (op == 0x11) {
                return fitSize(e, dstSize);
            }
            
            int copyLen = 0, backOff = 0;
            if (op >= 0x40) {
                copyLen = ((op & 0xF0) >> 4) - 1;
                int op2 = s.take();
                backOff = (op2 << 2) | ((op & 0x0C) >> 2);
                if ((op & 0x03) != 0) {
                    litLen = op & 0x03;
                } else {
                    lr = s.literalRun();
                    litLen = lr[0]; nextOp = lr[1];
                }
            } else {
                Form f = formOf(op);
                if (f == null) throw new Exception("非法 LZ77 压缩 opcode 0x" + Integer.toHexString(op));
                copyLen = f.lenBase;
                if (f.opMask == 0xFF) copyLen += op;
                else if (f.opMask == 0x0F) copyLen += (op & f.opMask);
                
                if (f.ext) copyLen += s.extendedCount();
                
                int[] sw = s.splitWord();
                backOff = sw[0] + f.offBase;
                int litCount = sw[1];
                if (litCount == 0) {
                    lr = s.literalRun();
                    litLen = lr[0]; nextOp = lr[1];
                } else {
                    litLen = litCount;
                }
            }
            
            e.appendWindow(backOff + 1, copyLen);
            if (litLen > 0) {
                if (s.at + litLen > src.length) throw new Exception("字面量游程超出压缩数据");
                e.append(src, s.at, litLen);
                s.at += litLen;
            }
        }
        
        return fitSize(e, dstSize);
    }
    
    private static byte[] fitSize(Emitter e, int dstSize) {
        byte[] out = new byte[dstSize];
        System.arraycopy(e.buf, 0, out, 0, Math.min(e.len, dstSize));
        return out;
    }
}
