package com.cadapp;

import com.javacad.bitstream.BitReader;
import com.javacad.container.R2004Container;
import com.javacad.entity.EntityDecoder;
import com.javacad.entity.EntityDecoder.*;
import com.javacad.objrec.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DwgParser {
    private static class Stroke {
        double x1, y1, x2, y2; int col; byte tid; short lid;
        Stroke(double x1, double y1, double x2, double y2, int col, byte tid, short lid) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.col = col; this.tid = tid; this.lid = lid;
        }
    }
    private static class Label {
        double x, y, h, rot; int col; byte anchor; short lid; String txt;
        Label(double x, double y, double h, double rot, int col, byte anchor, short lid, String txt) {
            this.x = x; this.y = y; this.h = h; this.rot = rot; this.col = col; this.anchor = anchor; this.lid = lid; this.txt = txt;
        }
    }
    private static class Xform {
        double sx = 1, sy = 1, cos = 1, sin = 0, tx = 0, ty = 0;
        double applyX(double x, double y) { return x * sx * cos - y * sy * sin + tx; }
        double applyY(double x, double y) { return x * sx * sin + y * sy * cos + ty; }
        Xform compose(double csx, double csy, double crot, double ctx, double cty) {
            Xform r = new Xform();
            double c = Math.cos(crot), s = Math.sin(crot);
            r.sx = sx * csx; r.sy = sy * csy;
            r.cos = cos * c - sin * s; r.sin = sin * c + cos * s;
            r.tx = tx + cos * sx * ctx - sin * sy * cty;
            r.ty = ty + sin * sx * ctx + cos * sy * cty;
            return r;
        }
    }

    private static int aciToRgb(int c, boolean isDarkMode) {
        int rgb = 0xFFFFFF;
        if ((c & 0x1000000) != 0) {
            rgb = c & 0xFFFFFF;
        } else {
            if (c == 1) rgb = 0xFF0000; else if (c == 2) rgb = 0xFFFF00; else if (c == 3) rgb = 0x00FF00;
            else if (c == 4) rgb = 0x00FFFF; else if (c == 5) rgb = 0x4080FF; else if (c == 6) rgb = 0xFF00FF;
            else if (c == 8) rgb = 0x808080; else if (c == 9) rgb = 0xC0C0C0; else if (c == 30) rgb = 0xFF8000;
            else if (c == 100) rgb = 0x00A854; else if (c == 126) rgb = 0x008080;
        }

        if (isDarkMode) {
            // 黑底模式：太暗的颜色(黑色/深灰)转为白色
            if (((rgb >> 16) & 0xFF) < 40 && ((rgb >> 8) & 0xFF) < 40 && (rgb & 0xFF) < 40) return 0xFFFFFFFF;
        } else {
            // 白底模式：纯白变纯黑，亮黄/亮青加深，防止看不清
            if (rgb == 0xFFFFFF || rgb == 0xC0C0C0) return 0xFF000000;
            if (rgb == 0xFFFF00) return 0xFFAAAA00;
            if (rgb == 0x00FFFF) return 0xFF008080;
        }
        return 0xFF000000 | rgb;
    }

    private static void addBulgeSeg(List<Stroke> out, double x1, double y1, double x2, double y2, double bg, int col, byte tid, short lid, Xform xf) {
        double dx = x2 - x1, dy = y2 - y1, chord = Math.hypot(dx, dy);
        if (Math.abs(bg) < 1e-6 || chord < 1e-9) {
            out.add(new Stroke(xf.applyX(x1, y1), xf.applyY(x1, y1), xf.applyX(x2, y2), xf.applyY(x2, y2), col, tid, lid));
            return;
        }
        double theta = 4.0 * Math.atan(bg);
        double r = chord / (2.0 * Math.sin(Math.abs(theta) / 2.0));
        double dist = Math.sqrt(Math.max(0.0, r * r - chord * chord / 4.0));
        if (Math.abs(bg) > 1.0) dist = -dist;
        double nx = -dy / chord, ny = dx / chord;
        if (bg < 0) { nx = -nx; ny = -ny; }
        double cx = (x1 + x2) / 2.0 + nx * dist, cy = (y1 + y2) / 2.0 + ny * dist;
        double a0 = Math.atan2(y1 - cy, x1 - cx);
        int segs = Math.max(8, (int)(Math.abs(theta) / (1.5 * Math.PI / 180.0)) + 1);
        double px = xf.applyX(x1, y1), py = xf.applyY(x1, y1);
        for (int k = 1; k <= segs; k++) {
            double ang = a0 + theta * k / segs;
            double lx = cx + Math.abs(r) * Math.cos(ang), ly = cy + Math.abs(r) * Math.sin(ang);
            double qx = xf.applyX(lx, ly), qy = xf.applyY(lx, ly);
            out.add(new Stroke(px, py, qx, qy, col, tid, lid)); px = qx; py = qy;
        }
    }

    private static void expandEntity(Base e, Xform xf, int depth, int parentCol, short parentLid,
                                     Map<Long, List<Base>> blocks, Map<Long, LayerInfo> layers,
                                     Map<Long, Short> layerMap, List<String> layerNames,
                                     List<Stroke> strokes, List<Label> labels, Set<Long> path, boolean isDarkMode) {
        if (depth > 10 || strokes.size() > 2000000) return;
        LayerInfo li = layers.get(e.layerHandle);
        int rgb = aciToRgb(e.color == 0 ? parentCol : ((e.color == 256 && li != null) ? li.color : e.color), isDarkMode);
        short lid = (e.layerHandle == 0 && parentLid >= 0) ? parentLid : layerMap.computeIfAbsent(e.layerHandle, h -> {
            layerNames.add(li != null && !li.name.trim().isEmpty() ? li.name : "0");
            return (short) (layerNames.size() - 1);
        });

        if (e instanceof InsertEnt) {
            InsertEnt ins = (InsertEnt) e;
            List<Base> children = blocks.get(ins.blockHeaderHandle);
            if (children == null || children.isEmpty() || path.contains(ins.blockHeaderHandle)) return;
            path.add(ins.blockHeaderHandle);
            int cols = Math.max(1, Math.min(500, ins.numCols));
            int rows = Math.max(1, Math.min(500, ins.numRows));
            double c = Math.cos(ins.rot), sn = Math.sin(ins.rot);
            for (int rIdx = 0; rIdx < rows; rIdx++) {
                for (int cIdx = 0; cIdx < cols; cIdx++) {
                    double lx = cIdx * ins.colSpacing, ly = rIdx * ins.rowSpacing;
                    double ox = ins.x + lx * c - ly * sn, oy = ins.y + lx * sn + ly * c;
                    Xform childXf = xf.compose(ins.sx, ins.sy, ins.rot, ox, oy);
                    for (Base ch : children) expandEntity(ch, childXf, depth + 1, rgb, lid, blocks, layers, layerMap, layerNames, strokes, labels, path, isDarkMode);
                }
            }
            path.remove(ins.blockHeaderHandle);
        } else if (e instanceof SolidEnt) {
            SolidEnt sd = (SolidEnt) e;
            double[] px = {sd.x1, sd.x2, sd.x4, sd.x3}, py = {sd.y1, sd.y2, sd.y4, sd.y3};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                strokes.add(new Stroke(xf.applyX(px[i], py[i]), xf.applyY(px[i], py[i]), xf.applyX(px[j], py[j]), xf.applyY(px[j], py[j]), rgb, (byte) 1, lid));
            }
        } else if (e instanceof Line) {
            Line l = (Line) e;
            strokes.add(new Stroke(xf.applyX(l.sx, l.sy), xf.applyY(l.sx, l.sy), xf.applyX(l.ex, l.ey), xf.applyY(l.ex, l.ey), rgb, (byte) 1, lid));
        } else if (e instanceof LwPoly && ((LwPoly) e).pts.size() >= 2) {
            LwPoly lp = (LwPoly) e; int n = lp.pts.size(), segs = lp.closed ? n : n - 1;
            for (int i = 0; i < segs; i++) {
                List<Double> p1 = lp.pts.get(i), p2 = lp.pts.get((i + 1) % n);
                addBulgeSeg(strokes, p1.get(0), p1.get(1), p2.get(0), p2.get(1), i < lp.bulges.size() ? lp.bulges.get(i) : 0.0, rgb, (byte) 2, lid, xf);
            }
        } else if (e instanceof Circle && ((Circle) e).r > 0) {
            Circle ci = (Circle) e;
            double px = xf.applyX(ci.cx + ci.r, ci.cy), py = xf.applyY(ci.cx + ci.r, ci.cy);
            for (int i = 1; i <= 180; i++) {
                double a = 2 * Math.PI * i / 180.0, lx = ci.cx + ci.r * Math.cos(a), ly = ci.cy + ci.r * Math.sin(a);
                double qx = xf.applyX(lx, ly), qy = xf.applyY(lx, ly);
                strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 3, lid)); px = qx; py = qy;
            }
        } else if (e instanceof Arc && ((Arc) e).r > 0) {
            Arc ar = (Arc) e;
            double span = (ar.a1 - ar.a0) % (2.0 * Math.PI);
            if (span <= 0) span += 2.0 * Math.PI;
            int segs = Math.max(12, Math.min(720, (int)(span / (Math.PI / 90.0)) + 1));
            double px = xf.applyX(ar.cx + ar.r * Math.cos(ar.a0), ar.cy + ar.r * Math.sin(ar.a0));
            double py = xf.applyY(ar.cx + ar.r * Math.cos(ar.a0), ar.cy + ar.r * Math.sin(ar.a0));
            for (int i = 1; i <= segs; i++) {
                double a = ar.a0 + span * i / segs, lx = ar.cx + ar.r * Math.cos(a), ly = ar.cy + ar.r * Math.sin(a);
                double qx = xf.applyX(lx, ly), qy = xf.applyY(lx, ly);
                strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 4, lid)); px = qx; py = qy;
            }
        } else if (e instanceof Ellipse) {
            Ellipse el = (Ellipse) e; double rx = Math.hypot(el.mx, el.my), ry = rx * el.ratio, ang = Math.atan2(el.my, el.mx), px = 0, py = 0;
            for (int i = 0; i <= 72; i++) {
                double t = el.a0 + (el.a1 - el.a0) * i / 72.0, lx = rx * Math.cos(t), ly = ry * Math.sin(t);
                double wx = el.cx + lx * Math.cos(ang) - ly * Math.sin(ang), wy = el.cy + lx * Math.sin(ang) + ly * Math.cos(ang);
                double qx = xf.applyX(wx, wy), qy = xf.applyY(wx, wy);
                if (i > 0) strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 5, lid)); px = qx; py = qy;
            }
        } else if (e instanceof DimEnt) {
            DimEnt d = (DimEnt) e;
            List<Base> anon = blocks.get(d.anonBlockHandle);
            if (anon != null && !anon.isEmpty() && !path.contains(d.anonBlockHandle)) {
                path.add(d.anonBlockHandle);
                for (Base ch : anon) expandEntity(ch, xf, depth + 1, rgb, lid, blocks, layers, layerMap, layerNames, strokes, labels, path, isDarkMode);
                path.remove(d.anonBlockHandle);
            } else if (!d.text.trim().isEmpty()) {
                double wx = xf.applyX(d.mx, d.my), wy = xf.applyY(d.mx, d.my);
                double dimH = (d.p13x != 0 && d.p14x != 0) ? Math.max(1.5, Math.hypot(d.p14x - d.p13x, d.p14y - d.p13y) * 0.08) : 2.5;
                labels.add(new Label(wx, wy, dimH * Math.abs(xf.sy), d.rot + Math.atan2(xf.sin, xf.cos), rgb, (byte) 1, lid, d.text.trim()));
            }
        } else if (e instanceof PointEnt) {
            PointEnt pt = (PointEnt) e;
            double wx = xf.applyX(pt.x, pt.y), wy = xf.applyY(pt.x, pt.y);
            double r = 0.25 * Math.abs(xf.sx);
            strokes.add(new Stroke(wx - r, wy, wx + r, wy, rgb, (byte) 7, lid));
            strokes.add(new Stroke(wx, wy - r, wx, wy + r, rgb, (byte) 7, lid));
        } else if (e instanceof HatchEnt) {
            HatchEnt he = (HatchEnt) e;
            for (HatchPath hp : he.paths) {
                if (hp.isPolyline && hp.pts.size() >= 2) {
                    int n = hp.pts.size(), segs = hp.closed ? n : n - 1;
                    for (int i = 0; i < segs; i++) {
                        List<Double> p1 = hp.pts.get(i), p2 = hp.pts.get((i + 1) % n);
                        addBulgeSeg(strokes, p1.get(0), p1.get(1), p2.get(0), p2.get(1), i < hp.bulges.size() ? hp.bulges.get(i) : 0.0, rgb, (byte) 8, lid, xf);
                    }
                } else {
                    for (double[] seg : hp.segLines) {
                        int st = (int) seg[0];
                        if (st == 1) {
                            strokes.add(new Stroke(xf.applyX(seg[1], seg[2]), xf.applyY(seg[1], seg[2]), xf.applyX(seg[3], seg[4]), xf.applyY(seg[3], seg[4]), rgb, (byte) 8, lid));
                        } else if (st == 2 && seg[3] > 0) {
                            double cx = seg[1], cy = seg[2], r = seg[3], a0 = seg[4], a1 = seg[5];
                            boolean ccw = seg[6] != 0;
                            double d = (a1 - a0) % (2 * Math.PI); if (d < 0) d += 2 * Math.PI;
                            double span = ccw ? d : (d == 0 ? -2 * Math.PI : d - 2 * Math.PI);
                            int segs = Math.max(12, Math.min(360, (int)(Math.abs(span) / (Math.PI / 60.0)) + 1));
                            double px = xf.applyX(cx + r * Math.cos(a0), cy + r * Math.sin(a0)), py = xf.applyY(cx + r * Math.cos(a0), cy + r * Math.sin(a0));
                            for (int i = 1; i <= segs; i++) {
                                double a = a0 + span * i / segs, qx = xf.applyX(cx + r * Math.cos(a), cy + r * Math.sin(a)), qy = xf.applyY(cx + r * Math.cos(a), cy + r * Math.sin(a));
                                strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 8, lid)); px = qx; py = qy;
                            }
                        } else if (st == 3) {
                            double cx = seg[1], cy = seg[2], mx = seg[3], my = seg[4], rat = seg[5], a0 = seg[6], a1 = seg[7];
                            boolean ccw = seg[8] != 0;
                            double rx = Math.hypot(mx, my), ry = rx * rat, ang = Math.atan2(my, mx);
                            double d = (a1 - a0) % (2 * Math.PI); if (d < 0) d += 2 * Math.PI;
                            double span = ccw ? d : (d == 0 ? -2 * Math.PI : d - 2 * Math.PI);
                            int segs = 64; double px = 0, py = 0;
                            for (int i = 0; i <= segs; i++) {
                                double t = a0 + span * i / segs, lx = rx * Math.cos(t), ly = ry * Math.sin(t);
                                double qx = xf.applyX(cx + lx * Math.cos(ang) - ly * Math.sin(ang), cy + lx * Math.sin(ang) + ly * Math.cos(ang));
                                double qy = xf.applyY(cx + lx * Math.cos(ang) - ly * Math.sin(ang), cy + lx * Math.sin(ang) + ly * Math.cos(ang));
                                if (i > 0) strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 8, lid)); px = qx; py = qy;
                            }
                        }
                    }
                }
            }
        } else if (e instanceof TextEnt && !((TextEnt) e).text.trim().isEmpty()) {
            TextEnt t = (TextEnt) e;
            double wx = xf.applyX(t.x, t.y), wy = xf.applyY(t.x, t.y);
            double rot = t.rot + Math.atan2(xf.sin, xf.cos);
            double h = (t.h > 0 ? t.h : 2.5) * Math.abs(xf.sy);
            labels.add(new Label(wx, wy, h, rot, rgb, (byte) t.anchor, lid, t.text.trim()));
        }
    }
    public static byte[] parseToBinary(byte[] dwgData, boolean isDarkMode) {
        try {
            Map<String, byte[]> secs = R2004Container.getSections(dwgData, "AcDb:Handles", "AcDb:AcDbObjects");
            List<ObjectRecord.ObjectRef> refs = HandlesParser.parseObjectMapHandles(secs.get("AcDb:Handles"));
            byte[] objData = secs.get("AcDb:AcDbObjects");

            Map<Long, LayerInfo> layers = new HashMap<>();
            List<Base> modelEnts = new ArrayList<>();
            Map<Long, List<Base>> blocks = new HashMap<>(); 

            for (ObjectRecord.ObjectRef ref : refs) {
                try {
                    ObjectRecord rec = ObjectRecord.parseObjectRecord(objData, ref, true);
                    int tc = ObjectRecord.parseObjHeader(rec).typeCode;
                    long endBit = rec.bodyBitOffset + rec.handleSizeFieldBits + (long) rec.size * 8 - rec.handleStreamSizeBits;
                    if (tc == 0x33) {
                        BitReader r = rec.bodyBitStream(); r.readUMC(); r.readOT();
                        LayerInfo li = EntityDecoder.decodeLayer(r, ref.handle, endBit);
                        if (li != null) layers.put(ref.handle, li);
                        continue;
                    }
                    boolean isDim = (tc >= 0x14 && tc <= 0x1A);
                    if (tc != 0x13 && tc != 0x12 && tc != 0x11 && tc != 0x4D && tc != 0x23 && tc != 0x01 && tc != 0x2C && tc != 0x07 && tc != 0x08 && tc != 0x1F && tc != 0x1B && tc != 0x4E && !isDim) continue;
                    for (boolean v : Arrays.asList(true, false)) {
                        BitReader r = rec.bodyBitStream(); r.readUMC(); r.readOT();
                        long saveH = r.tellBits();
                        Base hdr = EntityDecoder.parseHeaderAndLayer(r, ref.handle, v, endBit);
                        if (hdr == null) continue;
                        Base ent = null;
                        if (tc == 0x13) ent = EntityDecoder.decodeLine(r);
                        else if (tc == 0x12) ent = EntityDecoder.decodeCircle(r);
                        else if (tc == 0x11) ent = EntityDecoder.decodeArc(r);
                        else if (tc == 0x23) ent = EntityDecoder.decodeEllipse(r);
                        else if (tc == 0x4D) ent = EntityDecoder.decodeLwPoly(r);
                        else if (tc == 0x01) ent = EntityDecoder.decodeText(r, endBit);
                        else if (tc == 0x2C) ent = EntityDecoder.decodeMText(r, endBit);
                        else if (tc == 0x1F) ent = EntityDecoder.decodeSolid(r);
                        else if (tc == 0x1B) ent = EntityDecoder.decodePoint(r);
                        
                        else if (tc == 0x07 || tc == 0x08 || isDim) {
                            BitReader r2 = rec.bodyBitStream(); r2.setBitPos(saveH);
                            r2.readH();
                            while (true) { int l = r2.readBS(); if (l == 0) break; r2.readH(); for (int i = 0; i < l; i++) r2.readRC(); }
                            if (r2.readB() == 1) { long g = r2.readBL(); for (long i = 0; i < g; i++) r2.readRC(); }
                            int m = r2.readBB(); long rc = r2.readBL(); int xm = r2.readB(); r2.readB();
                            r2.readColorIndex(); r2.readBD(); int lt = r2.readBB(), ps = r2.readBB(), mt = r2.readBB();
                            if (v) r2.readRC();
                            r2.readB(); r2.readB(); r2.readB(); r2.readBS(); if (v) r2.readRC();
                            if (tc == 0x07) ent = EntityDecoder.decodeInsert(r2, ref.handle, m, rc, xm, lt, ps, mt, endBit);
                            else if (tc == 0x08) ent = EntityDecoder.decodeMInsert(r2, ref.handle, m, rc, xm, lt, ps, mt, endBit);
                            else ent = EntityDecoder.decodeDim(r2, tc, ref.handle, m, rc, xm, lt, ps, mt, endBit);
                        }
                        if (ent != null) {
                            ent.color = hdr.color; ent.mode = hdr.mode;
                            ent.ownerHandle = hdr.ownerHandle; ent.layerHandle = hdr.layerHandle;
                            if (ent.mode == 2) modelEnts.add(ent);
                            else if (ent.mode == 0 && ent.ownerHandle != 0) blocks.computeIfAbsent(ent.ownerHandle, k -> new ArrayList<>()).add(ent);
                        }
                        break;
                    }
                } catch (Exception ignored) {}
            }

            List<String> typeNames = Arrays.asList("TEXT", "LINE", "LWPOLYLINE", "CIRCLE", "ARC", "ELLIPSE", "DIMENSION", "POINT", "HATCH");
            Map<Long, Short> layerMap = new LinkedHashMap<>();
            List<String> layerNames = new ArrayList<>();
            List<Stroke> strokes = new ArrayList<>(modelEnts.size() * 2);
            List<Label> labels = new ArrayList<>();
            Xform rootXf = new Xform();
            Set<Long> path = new HashSet<>();

            for (Base e : modelEnts) expandEntity(e, rootXf, 0, 7, (short) -1, blocks, layers, layerMap, layerNames, strokes, labels, path, isDarkMode);

            double minX = 1e30, minY = 1e30, maxX = -1e30, maxY = -1e30;
            List<Double> xs = new ArrayList<>(strokes.size()), ys = new ArrayList<>(strokes.size());
            for (Stroke s : strokes) {
                xs.add(s.x1); ys.add(s.y1);
                minX = Math.min(minX, Math.min(s.x1, s.x2)); maxX = Math.max(maxX, Math.max(s.x1, s.x2));
                minY = Math.min(minY, Math.min(s.y1, s.y2)); maxY = Math.max(maxY, Math.max(s.y1, s.y2));
            }
            for (Label lb : labels) {
                minX = Math.min(minX, lb.x); maxX = Math.max(maxX, lb.x);
                minY = Math.min(minY, lb.y); maxY = Math.max(maxY, lb.y);
            }
            double gcx = 0, gcy = 0;
            if (!xs.isEmpty()) {
                Collections.sort(xs); Collections.sort(ys);
                gcx = xs.get(xs.size() / 2); gcy = ys.get(ys.size() / 2);
            } else { minX = 0; minY = 0; maxX = 100; maxY = 100; }

            ByteBuffer dict = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
            dict.put((byte) typeNames.size());
            for (String tn : typeNames) { byte[] b = tn.getBytes(StandardCharsets.UTF_8); dict.put((byte) b.length); dict.put(b); }
            dict.putShort((short) layerNames.size());
            for (String ln : layerNames) { byte[] b = ln.getBytes(StandardCharsets.UTF_8); int l = Math.min(255, b.length); dict.put((byte) l); dict.put(b, 0, l); }
            int dictLen = dict.position();

            int labelBytes = 0;
            List<Object> lbUtf8 = new ArrayList<>(labels.size());
            for (Label lb : labels) { byte[] b = lb.txt.getBytes(StandardCharsets.UTF_8); lbUtf8.add(b); labelBytes += 25 + Math.min(65535, b.length); }

            ByteBuffer out = ByteBuffer.allocate(44 + dictLen + strokes.size() * 23 + labelBytes).order(ByteOrder.LITTLE_ENDIAN);
            out.putDouble(gcx); out.putDouble(gcy); out.putInt(strokes.size()); out.putInt(labels.size());
            out.putFloat((float)(minX - gcx)); out.putFloat((float)(minY - gcy));
            out.putFloat((float)(maxX - gcx)); out.putFloat((float)(maxY - gcy));
            out.putInt(dictLen); out.put(dict.array(), 0, dictLen);

            for (Stroke s : strokes) {
                out.putFloat((float)(s.x1 - gcx)); out.putFloat((float)(s.y1 - gcy));
                out.putFloat((float)(s.x2 - gcx)); out.putFloat((float)(s.y2 - gcy));
                out.putInt(s.col); out.put(s.tid); out.putShort(s.lid);
            }
            for (int i = 0; i < labels.size(); i++) {
                Label lb = labels.get(i); byte[] b = (byte[]) lbUtf8.get(i); int l = Math.min(65535, b.length);
                out.putFloat((float)(lb.x - gcx)); out.putFloat((float)(lb.y - gcy));
                out.putFloat((float) lb.h); out.putFloat((float) lb.rot);
                out.putInt(lb.col); out.put(lb.anchor); out.putShort(lb.lid);
                out.putShort((short) l); out.put(b, 0, l);
            }
                        return out.array();
        } catch (Throwable t) { t.printStackTrace(); return null; }
    }
}