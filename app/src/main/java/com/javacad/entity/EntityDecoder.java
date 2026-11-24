package com.javacad.entity;

import com.javacad.bitstream.BitReader;
import java.util.*;

public class EntityDecoder {
    public static class LayerInfo {
        public String name = "0";
        public int color = 7;
    }

    public static class Base {
        public int color = 7;
        public int mode = 2;
        public long ownerHandle;
        public long layerHandle;
        public String layerName = "0";
    }

    public static class Line extends Base {
        public double sx, sy, ex, ey;
    }

    public static class Circle extends Base {
        public double cx, cy, r;
    }

    public static class Arc extends Base {
        public double cx, cy, r, a0, a1;
    }

    public static class Ellipse extends Base {
        public double cx, cy, mx, my, ratio, a0, a1;
    }

    public static class LwPoly extends Base {
        public List<List<Double>> pts = new ArrayList<>();
        public List<Double> bulges = new ArrayList<>();
        public boolean closed;
    }

    public static class TextEnt extends Base {
        public String text = "";
        public double x, y, h, rot;
        public int anchor = 0;
    }

    public static class InsertEnt extends Base {
        public double x, y, sx = 1.0, sy = 1.0, rot;
        public int numCols = 1, numRows = 1;
        public double colSpacing = 0, rowSpacing = 0;
        public long blockHeaderHandle;
    }
    public static class SolidEnt extends Base {
        public double x1, y1, x2, y2, x3, y3, x4, y4;
    }

    public static class PointEnt extends Base {
        public double x, y;
    }
    public static class HatchPath {
        public List<List<Double>> pts = new ArrayList<>();
        public List<Double> bulges = new ArrayList<>();
        public boolean isPolyline, closed;
        public List<double[]> segLines = new ArrayList<>();
    }
    public static class HatchEnt extends Base {
        public List<HatchPath> paths = new ArrayList<>();
    }
    public static class DimEnt extends Base {
        public double mx, my, val, rot, p13x, p13y, p14x, p14y, p10x, p10y;
        public String text = "";
        public long anonBlockHandle = 0;
    }

    public static LayerInfo decodeLayer(BitReader r, long expectH, long endBit) {
        if (r.readH() != expectH) return null;
        while (true) {
            int len = r.readBS();
            if (len == 0) break;
            r.readH();
            for (int i = 0; i < len; i++) r.readRC();
        }
        r.readBL();
        r.readB();
        r.readB();
        r.readBS();
        r.readBS();
        int idx = r.readBS();
        long rgb = r.readBL();
        r.readRC();
        LayerInfo info = new LayerInfo();
        String n = readTailString(r, endBit);
        if (!n.trim().isEmpty()) info.name = n;
        int c = idx & 0x1FF;
        if ((rgb & 0xFF) > 0) c = (int) (rgb & 0xFF);
        if (c == 0 || c > 255) c = 7;

        info.color = c;
        return info;
    }

    public static Base parseHeaderAndLayer(BitReader r, long expectH, boolean shadowLw, long endBit) {
        if (r.readH() != expectH) return null;
        while (true) {
            int len = r.readBS();
            if (len == 0) break;
            r.readH();
            for (int i = 0; i < len; i++) r.readRC();
        }
        if (r.readB() == 1) {
            long g = r.readBL();
            for (long i = 0; i < g; i++) r.readRC();
        }
        int mode = r.readBB();
        if (mode == 1) return null;
        long reactors = r.readBL();
        if (reactors > 100000) return null;
        int xdicMissing = r.readB();
        r.readB();
        int col = r.readColorIndex();
        r.readBD();
        r.readBB();
        r.readBB();
        r.readBB();
        if (shadowLw) r.readRC();
        r.readB();
        r.readB();
        r.readB();
        r.readBS();
        if (shadowLw) r.readRC();

        long save = r.tellBits();
        long ownerH = 0, layerH = 0;
        try {
            r.setBitPos(endBit);
            if (mode == 0) ownerH = r.readHandleRef(expectH);
            for (long i = 0; i < reactors; i++) r.readHandleRef(expectH);
            if (xdicMissing == 0) r.readHandleRef(expectH);
            layerH = r.readHandleRef(expectH);
        } catch (Exception ignored) {
        }
        r.setBitPos(save);

        Base b = new Base();
        b.color = col;
        b.mode = mode;
        b.ownerHandle = ownerH;
        b.layerHandle = layerH;
        return b;
    }

    public static String readTailString(BitReader r, long objSizeBit) {
        if (objSizeBit < 40) return "";
        long save = r.tellBits();
        try {
            r.setBitPos(objSizeBit - 1);
            if (r.readB() == 0) return "";
            r.setBitPos(objSizeBit - 17);
            int ds = r.readRS();
            if ((ds & 0x8000) != 0) {
                r.setBitPos(objSizeBit - 33);
                ds = (ds & 0x7FFF) | (r.readRS() << 15);
            }
            long start = objSizeBit - 17 - ds;
            if (start < 0 || ds <= 0 || ds > 100000) return "";
            r.setBitPos(start);
            return r.readTU();
        } catch (Exception e) {
            return "";
        } finally {
            r.setBitPos(save);
        }
    }

    public static Line decodeLine(BitReader r) {
        Line l = new Line();
        int z0 = r.readB();
        l.sx = r.readRD();
        l.ex = r.readDD(l.sx);
        l.sy = r.readRD();
        l.ey = r.readDD(l.sy);
        if (z0 == 0) {
            double z = r.readRD();
            r.readDD(z);
        }
        r.readBT();
        r.readBE();
        return l;
    }

    public static Circle decodeCircle(BitReader r) {
        Circle c = new Circle();
        c.cx = r.readBD();
        c.cy = r.readBD();
        r.readBD();
        c.r = r.readBD();
        r.readBT();
        double[] ext = r.readBE();
        if (ext != null && ext.length >= 3 && ext[2] < 0) c.cx = -c.cx;
        return c;
    }

    public static Arc decodeArc(BitReader r) {
        Arc a = new Arc();
        a.cx = r.readBD();
        a.cy = r.readBD();
        r.readBD();
        a.r = r.readBD();
        r.readBT();
        double[] ext = r.readBE();
        a.a0 = r.readBD();
        a.a1 = r.readBD();
        if (ext != null && ext.length >= 3 && ext[2] < 0) {
            a.cx = -a.cx;
            double na0 = Math.PI - a.a1, na1 = Math.PI - a.a0;
            a.a0 = na0; a.a1 = na1;
        }
        return a;
    }

    public static Ellipse decodeEllipse(BitReader r) {
        Ellipse e = new Ellipse();
        e.cx = r.readBD();
        e.cy = r.readBD();
        r.readBD();
        e.mx = r.readBD();
        e.my = r.readBD();
        r.readBD();
        r.read3BD();
        e.ratio = r.readBD();
        e.a0 = r.readBD();
        e.a1 = r.readBD();
        return e;
    }

    public static LwPoly decodeLwPoly(BitReader r) {
        LwPoly lp = new LwPoly();
        int flags = r.readBS();
        if ((flags & 0x04) != 0) r.readBD();
        if ((flags & 0x08) != 0) r.readBD();
        if ((flags & 0x02) != 0) r.readBD();
        boolean flipX = false;
        if ((flags & 0x01) != 0) {
            double ex = r.readBD(), ey = r.readBD(), ez = r.readBD();
            if (ez < 0) flipX = true;
        }
        int n = (int) r.readBL();
        if (n <= 0 || n > 100000) return lp;
        int nb = ((flags & 0x10) != 0) ? (int) r.readBL() : 0;
        int nid = ((flags & 0x400) != 0) ? (int) r.readBL() : 0;
        int nw = ((flags & 0x20) != 0) ? (int) r.readBL() : 0;
        double x = r.readRD(), y = r.readRD();
        lp.pts.add(List.of(flipX ? -x : x, y));
        for (int i = 1; i < n; i++) {
            x = r.readDD(x);
            y = r.readDD(y);
            lp.pts.add(List.of(flipX ? -x : x, y));
        }
        for (int i = 0; i < nb; i++) {
            double bg = r.readBD();
            lp.bulges.add(flipX ? -bg : bg);
        }
        lp.closed = (flags & 0x201) != 0;
        return lp;
    }

    public static TextEnt decodeText(BitReader r, long endBit) {
        TextEnt t = new TextEnt();
        int df = r.readRC();
        if ((df & 0x01) == 0) r.readRD();
        t.x = r.readRD();
        t.y = r.readRD();
        double ax = t.x, ay = t.y;
        boolean hasAlign = (df & 0x02) == 0;
        if (hasAlign) {
            ax = r.readDD(t.x);
            ay = r.readDD(t.y);
        }
        r.readBE();
        r.readBT();
        if ((df & 0x04) == 0) r.readRD();
        if ((df & 0x08) == 0) t.rot = r.readRD();
        t.h = r.readRD();
        if ((df & 0x10) == 0) r.readRD();
        if ((df & 0x20) == 0) r.readBS();
        int hAlign = ((df & 0x40) == 0) ? r.readBS() : 0;
        int vAlign = ((df & 0x80) == 0) ? r.readBS() : 0;
        if ((hAlign != 0 || vAlign != 0) && hasAlign) {
            t.x = ax;
            t.y = ay;
        }
        t.anchor = (hAlign == 1 || hAlign == 4) ? 1 : (hAlign == 2 ? 2 : 0);
        t.text = readTailString(r, endBit);
        return t;
    }

    public static TextEnt decodeMText(BitReader r, long endBit) {
        TextEnt t = new TextEnt();
        t.x = r.readBD();
        t.y = r.readBD();
        r.readBD();
        r.read3BD();
        double dx = r.readBD(), dy = r.readBD();
        r.readBD();
        t.rot = Math.atan2(dy, dx);
        r.readBD();
        r.readBD();
        t.h = r.readBD();
        int att = r.readBS();
        if (att >= 1 && att <= 9) t.anchor = (att - 1) % 3;
        t.text = readTailString(r, endBit).replaceAll("\\\\[A-Za-z][^;]*;|\\{|\\}", "").replace("\\P", " ");
        return t;
    }

    public static DimEnt decodeDim(BitReader r, int tc, long expectH, int mode, long reactors, int xdicMissing, int ltFlags, int psFlags, int matFlags, long endBit) {
        DimEnt d = new DimEnt();
        r.readRC();
        r.read3BD();
        d.mx = r.readRD();
        d.my = r.readRD();
        r.readBD();
        r.readRC();
        d.text = readTailString(r, endBit);
        r.readBD();
        r.readBD();
        r.read3BD();
        r.readBD();
        r.readBS();
        r.readBS();
        r.readBD();
        d.val = r.readBD();
        r.readB();
        r.readB();
        r.readB();
        r.readRD();
        r.readRD();
        if (tc == 0x15 || tc == 0x16) {
            d.p13x = r.readBD();
            d.p13y = r.readBD();
            r.readBD();
            d.p14x = r.readBD();
            d.p14y = r.readBD();
            r.readBD();
            d.p10x = r.readBD();
            d.p10y = r.readBD();
            r.readBD();
        } else if (tc == 0x14 || tc == 0x17) {
            d.p10x = r.readBD();
            d.p10y = r.readBD();
            r.readBD();
            d.p13x = r.readBD();
            d.p13y = r.readBD();
            r.readBD();
            d.p14x = r.readBD();
            d.p14y = r.readBD();
            r.readBD();
        } else if (tc == 0x19) {
            d.p10x = r.readBD();
            d.p10y = r.readBD();
            r.readBD();
            d.p13x = r.readBD();
            d.p13y = r.readBD();
            r.readBD();
        } else if (tc == 0x1A) {
            d.p13x = r.readBD();
            d.p13y = r.readBD();
            r.readBD();
            d.p10x = r.readBD();
            d.p10y = r.readBD();
            r.readBD();
        }
        if (d.text.trim().isEmpty() || d.text.contains("<>")) {
            String num = String.format("%.2f", d.val);
            d.text = d.text.trim().isEmpty() ? num : d.text.replace("<>", num);
        }
        long save = r.tellBits();
        try {
            r.setBitPos(endBit);
            if (mode == 0) r.readHandleRef(expectH);
            for (long i = 0; i < reactors; i++) r.readHandleRef(expectH);
            if (xdicMissing == 0) r.readHandleRef(expectH);
            r.readHandleRef(expectH); // layer
            if (ltFlags == 3) r.readHandleRef(expectH);
            if (matFlags == 3) r.readHandleRef(expectH);
            if (psFlags == 3) r.readHandleRef(expectH);
            r.readHandleRef(expectH); // dimStyle
            d.anonBlockHandle = r.readHandleRef(expectH); // *Dxx 匿名块
        } catch (Exception ignored) {}
        r.setBitPos(save);
        return d;
    }

    public static InsertEnt decodeInsert(BitReader r, long expectH, int mode, long reactors, int xdicMissing, int ltFlags, int psFlags, int matFlags, long endBit) {
        InsertEnt ins = new InsertEnt();
        ins.x = r.readBD();
        ins.y = r.readBD();
        r.readBD();
        int sf = r.readBB();
        if (sf == 1) {
            ins.sy = r.readDD(1.0);
            r.readDD(1.0);
        } else if (sf == 2) {
            ins.sx = r.readRD();
            ins.sy = ins.sx;
        } else if (sf == 0) {
            ins.sx = r.readRD();
            ins.sy = r.readDD(ins.sx);
            r.readDD(ins.sx);
        }
        ins.rot = r.readBD();
        r.read3BD();
        int hasAtt = r.readB();
        if (hasAtt == 1) r.readBL();
        long save = r.tellBits();
        try {
            r.setBitPos(endBit);
            if (mode == 0) r.readHandleRef(expectH);
            for (long i = 0; i < reactors; i++) r.readHandleRef(expectH);
            if (xdicMissing == 0) r.readHandleRef(expectH);
            r.readHandleRef(expectH); // layer
            if (ltFlags == 3) r.readHandleRef(expectH);
            if (matFlags == 3) r.readHandleRef(expectH);
            if (psFlags == 3) r.readHandleRef(expectH);
            ins.blockHeaderHandle = r.readHandleRef(expectH);
        } catch (Exception ignored) {
        }
        r.setBitPos(save);
        return ins;
    }

    public static InsertEnt decodeMInsert(BitReader r, long expectH, int mode, long reactors, int xdicMissing, int ltFlags, int psFlags, int matFlags, long endBit) {
        InsertEnt ins = new InsertEnt();
        ins.x = r.readBD(); ins.y = r.readBD(); r.readBD();
        int sf = r.readBB();
        if (sf == 1) { ins.sy = r.readDD(1.0); r.readDD(1.0); }
        else if (sf == 2) { ins.sx = r.readRD(); ins.sy = ins.sx; }
        else if (sf == 0) { ins.sx = r.readRD(); ins.sy = r.readDD(ins.sx); r.readDD(ins.sx); }
        ins.rot = r.readBD();
        r.read3BD();
        int hasAtt = r.readB();
        if (hasAtt == 1) r.readBL();
        ins.numCols = r.readBS();
        ins.numRows = r.readBS();
        ins.colSpacing = r.readBD();
        ins.rowSpacing = r.readBD();
        long save = r.tellBits();
        try {
            r.setBitPos(endBit);
            if (mode == 0) r.readHandleRef(expectH);
            for (long i = 0; i < reactors; i++) r.readHandleRef(expectH);
            if (xdicMissing == 0) r.readHandleRef(expectH);
            r.readHandleRef(expectH);
            if (ltFlags == 3) r.readHandleRef(expectH);
            if (matFlags == 3) r.readHandleRef(expectH);
            if (psFlags == 3) r.readHandleRef(expectH);
            ins.blockHeaderHandle = r.readHandleRef(expectH);
        } catch (Exception ignored) {}
        r.setBitPos(save);
        return ins;
    }

    public static SolidEnt decodeSolid(BitReader r) {
        SolidEnt sd = new SolidEnt();
        r.readBT(); r.readBD();
        sd.x1 = r.readRD(); sd.y1 = r.readRD();
        sd.x2 = r.readRD(); sd.y2 = r.readRD();
        sd.x3 = r.readRD(); sd.y3 = r.readRD();
        sd.x4 = r.readRD(); sd.y4 = r.readRD();
        double[] ext = r.readBE();
        if (ext != null && ext.length >= 3 && ext[2] < 0) {
            sd.x1 = -sd.x1; sd.x2 = -sd.x2; sd.x3 = -sd.x3; sd.x4 = -sd.x4;
        }
        return sd;
    }
    public static PointEnt decodePoint(BitReader r) {
        PointEnt pt = new PointEnt();
        pt.x = r.readBD(); pt.y = r.readBD(); r.readBD();
        r.readBT(); r.readBE(); r.readBD();
        return pt;
    }

    private static void skipCMC(BitReader r) {
        r.readBS(); r.readBL();
        int f = r.readRC();
        if (f < 4) { if ((f & 1) != 0) r.readTU(); if ((f & 2) != 0) r.readTU(); }
    }

    public static HatchEnt decodeHatch(BitReader r) {
        HatchEnt h = new HatchEnt();
        try {
            r.readBL(); r.readBL(); r.readBD(); r.readBD(); r.readBL(); r.readBD();
            long nc = r.readBL();
            if (nc < 0 || nc > 1000) return h;
            for (long i = 0; i < nc; i++) { r.readBD(); skipCMC(r); }
            r.readBD(); r.read3BD(); r.readB(); r.readB();
            long np = r.readBL();
            if (np <= 0 || np > 50000) return h;
            for (long i = 0; i < np; i++) {
                long flag = r.readBL();
                HatchPath hp = new HatchPath();
                if ((flag & 0x02) != 0) {
                    hp.isPolyline = true;
                    int hasBulge = r.readB();
                    hp.closed = r.readB() != 0;
                    long nv = r.readBL();
                    if (nv < 0 || nv > 100000) break;
                    for (long j = 0; j < nv; j++) {
                        double x = r.readRD(), y = r.readRD();
                        hp.pts.add(List.of(x, y));
                        hp.bulges.add(hasBulge != 0 ? r.readBD() : 0.0);
                    }
                    r.readBL();
                } else {
                    long ns = r.readBL();
                    if (ns < 0 || ns > 100000) break;
                    for (long j = 0; j < ns; j++) {
                        int st = r.readRC();
                        if (st == 1) {
                            hp.segLines.add(new double[]{1, r.readRD(), r.readRD(), r.readRD(), r.readRD()});
                        } else if (st == 2) {
                            double cx = r.readRD(), cy = r.readRD(), rad = r.readBD(), a0 = r.readBD(), a1 = r.readBD();
                            int ccw = r.readB();
                            hp.segLines.add(new double[]{2, cx, cy, rad, a0, a1, ccw});
                        } else if (st == 3) {
                            double cx = r.readRD(), cy = r.readRD(), ex = r.readRD(), ey = r.readRD(), rat = r.readBD(), a0 = r.readBD(), a1 = r.readBD();
                            int ccw = r.readB();
                            hp.segLines.add(new double[]{3, cx, cy, ex, ey, rat, a0, a1, ccw});
                        } else if (st == 4) {
                            long deg = r.readBL(); int rat = r.readB(); r.readB();
                            long nk = r.readBL(), ncp = r.readBL();
                            if (deg > 25 || nk > 100000 || ncp > 100000) break;
                            for (long k = 0; k < nk; k++) r.readBD();
                            List<double[]> cps = new ArrayList<>();
                            for (long k = 0; k < ncp; k++) {
                                double px = r.readRD(), py = r.readRD();
                                if (rat != 0) r.readBD();
                                cps.add(new double[]{px, py});
                            }
                            long nfp = r.readBL();
                            for (long k = 0; k < nfp; k++) { r.readRD(); r.readRD(); }
                            r.readRD(); r.readRD(); r.readRD(); r.readRD();
                            for (int k = 0; k + 1 < cps.size(); k++) {
                                hp.segLines.add(new double[]{1, cps.get(k)[0], cps.get(k)[1], cps.get(k+1)[0], cps.get(k+1)[1]});
                            }
                        } else break;
                    }
                    r.readBL();
                }
                h.paths.add(hp);
            }
        } catch (Exception ignored) {}
        return h;
    }
}