package com.cadapp;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

public class HzShxFontTool {
    public static class Pt {
        public final double x, y;
        public Pt(double x, double y) { this.x = x; this.y = y; }
    }
    public static class Bounds {
        public final double minX, minY, maxX, maxY;
        public Bounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
        }
        public double width() { return Math.max(0.0, maxX - minX); }
    }
    public static class LayoutData {
        public final List<List<Pt>> localPolylines;
        public final Bounds localBounds;
        public LayoutData(List<List<Pt>> polys, Bounds bounds) {
            this.localPolylines = polys; this.localBounds = bounds;
        }
        public double width() { return localBounds.width(); }
    }

    private final Map<Integer, List<List<Pt>>> shxGlyphs = new HashMap<>();

    public HzShxFontTool(InputStream... streams) {
        for (InputStream is : streams) {
            if (is == null) continue;
            try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                parseShxData(bos.toByteArray());
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void parseShxData(byte[] data) {
        Map<Integer, List<List<Pt>>> indexed = parseIndexTable(data);
        if (indexed.size() >= 64) {
            for (Map.Entry<Integer, List<List<Pt>>> e : indexed.entrySet()) {
                shxGlyphs.putIfAbsent(e.getKey(), e.getValue());
            }
            return;
        }
        for (int off = 24; off + 8 <= data.length; off++) {
            int code = u16LE(data, off);
            int len = u16LE(data, off + 2);
            long dataOff = u32LE(data, off + 4);
            if (isValidGbCode(code) && len >= 4 && len <= 4096 && dataOff >= 24 && dataOff + len <= data.length) {
                List<List<Pt>> polys = decodeStrokes(data, (int) dataOff, len);
                if (!polys.isEmpty()) shxGlyphs.putIfAbsent(code, polys);
            }
        }
    }

    private static Map<Integer, List<List<Pt>>> parseIndexTable(byte[] data) {
        Map<Integer, List<List<Pt>>> best = new HashMap<>();
        if (data.length < 32) return best;

        for (int countOff : new int[]{18, 20, 22, 24, 26}) {
            if (countOff + 4 > data.length) continue;
            int count = u16LE(data, countOff);
            if (count <= 0 || count > 20000) continue;

            for (int tableStart : new int[]{countOff + 2, countOff + 4, 24, 26, 28}) {
                long tableEnd = (long) tableStart + (long) count * 8L;
                if (tableStart < 16 || tableEnd > data.length) continue;

                Map<Integer, List<List<Pt>>> candidate = new HashMap<>();
                int validCount = 0;
                for (int idx = 0; idx < count; idx++) {
                    int off = tableStart + idx * 8;
                    int code = u16LE(data, off);
                    int len = u16LE(data, off + 2);
                    long dataOff = u32LE(data, off + 4);
                    if (isValidGbCode(code) && len >= 4 && len <= 4096 && dataOff >= tableEnd && dataOff + len <= data.length) {
                        List<List<Pt>> polys = decodeStrokes(data, (int) dataOff, len);
                        if (!polys.isEmpty()) {
                            candidate.put(code, polys);
                            validCount++;
                        }
                    }
                }
                if (validCount > best.size()) best = candidate;
            }
        }
        return best;
    }

    private static boolean isValidGbCode(int code) {
        int hi = (code >> 8) & 0xFF, lo = code & 0xFF;
        return hi >= 0xA1 && hi <= 0xFE && lo >= 0xA1 && lo <= 0xFE;
    }

    private static int u16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32LE(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | (((long) (b[off + 1] & 0xFF)) << 8)
                | (((long) (b[off + 2] & 0xFF)) << 16)
                | (((long) (b[off + 3] & 0xFF)) << 24);
    }

    private static boolean isReasonableCoord(int x, int y) {
        return x >= -512 && x <= 512 && y >= -512 && y <= 512;
    }

    public List<List<Pt>> getGlyphPolylines(char ch) {
        List<List<Pt>> fullWidth = fullWidthPunctuationGlyph(ch);
        if (!fullWidth.isEmpty()) return fullWidth;
        if (ch < 128) return asciiGlyph(ch);
        try {
            byte[] gb = String.valueOf(ch).getBytes(Charset.forName("GB2312"));
            if (gb.length >= 2) {
                int b0 = gb[0] & 0xFF, b1 = gb[1] & 0xFF;
                List<List<Pt>> g = shxGlyphs.get((b0 << 8) | b1);
                if (g == null) g = shxGlyphs.get((b1 << 8) | b0);
                if (g != null) return g;
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    public LayoutData layoutLocal(String text, double scale) {
        List<List<Pt>> outPolys = new ArrayList<>();
        double cursorX = 0.0, baselineY = 0.0, cellAdvance = 95.0 * scale;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        Boolean prevIsAscii = null;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') { cursorX = 0.0; baselineY -= 155.0 * scale; prevIsAscii = null; continue; }
            if (Character.isWhitespace(ch)) { cursorX += cellAdvance * 0.45; prevIsAscii = null; continue; }
            boolean curIsAscii = ch < 128;
            if (prevIsAscii != null && prevIsAscii != curIsAscii) cursorX += 10.0 * scale;
            prevIsAscii = curIsAscii;

            List<List<Pt>> polys = getGlyphPolylines(ch);
            if (!polys.isEmpty()) {
                double gMinX = Double.POSITIVE_INFINITY, gMaxX = Double.NEGATIVE_INFINITY;
                for (List<Pt> pl : polys) for (Pt p : pl) { gMinX = Math.min(gMinX, p.x); gMaxX = Math.max(gMaxX, p.x); }
                double glyphW = (ch == '.' || ch == ',' || ch == ':' || ch == '：' || ch == '，' || ch == '．')
                        ? Math.max(12.0, gMaxX - gMinX)
                        : Math.max(24.0, gMaxX - gMinX);
                for (List<Pt> pl : polys) {
                    if (pl.size() < 2) continue;
                    List<Pt> tr = new ArrayList<>(pl.size());
                    for (Pt p : pl) {
                        double sx = cursorX + (p.x - gMinX) * scale;
                        double sy = baselineY + p.y * scale;
                        minX = Math.min(minX, sx); minY = Math.min(minY, sy);
                        maxX = Math.max(maxX, sx); maxY = Math.max(maxY, sy);
                        tr.add(new Pt(sx, sy));
                    }
                    outPolys.add(tr);
                }
                cursorX += (glyphW + ((ch == '.' || ch == ',' || ch == '．' || ch == '，') ? 10.0 : 20.0)) * scale;
            } else cursorX += cellAdvance * 0.6;
        }
        Bounds b = (minX == Double.POSITIVE_INFINITY) ? new Bounds(0, 0, 0, 0) : new Bounds(minX, minY, maxX, maxY);
        return new LayoutData(outPolys, b);
    }

    private static List<List<Pt>> decodeStrokes(byte[] data, int start, int len) {
        int end = start + len, i = Math.min(start + 2, end), x = 0, y = 0;
        List<List<Pt>> polylines = new ArrayList<>(); List<Pt> cur = new ArrayList<>();
        while (i + 1 < end) {
            int op1 = data[i] & 0xFF, op2 = data[i + 1] & 0xFF;
            if (op1 == 0x00 && op2 == 0x00) { if (!cur.isEmpty()) { polylines.add(cur); cur = new ArrayList<>(); } i += 2; }
            else if (op1 == 0x02 && op2 == 0x08 && i + 3 < end) {
                if (!cur.isEmpty()) { polylines.add(cur); cur = new ArrayList<>(); }
                int nx = x + data[i + 2], ny = y + data[i + 3];
                if (!isReasonableCoord(nx, ny)) return Collections.emptyList();
                x = nx; y = ny; cur.add(new Pt(x, y)); i += 4;
            } else if (op1 == 0x01 && op2 == 0x08 && i + 3 < end) {
                if (cur.isEmpty()) cur.add(new Pt(x, y));
                int nx = x + data[i + 2], ny = y + data[i + 3];
                if (!isReasonableCoord(nx, ny)) return Collections.emptyList();
                x = nx; y = ny; cur.add(new Pt(x, y)); i += 4;
            } else if (op1 == 0x01 && op2 == 0x09) {
                if (cur.isEmpty()) cur.add(new Pt(x, y)); i += 2;
                while (i + 1 < end) {
                    int b0 = data[i] & 0xFF, b1 = data[i + 1] & 0xFF;
                    if ((b0 == 0x00 && b1 == 0x00) || ((b0 == 0x01 || b0 == 0x02) && (b1 == 0x08 || b1 == 0x09))) break;
                    int nx = x + data[i], ny = y + data[i + 1];
                    if (!isReasonableCoord(nx, ny)) return Collections.emptyList();
                    x = nx; y = ny; cur.add(new Pt(x, y)); i += 2;
                }
            } else i += 1;
        }
        if (!cur.isEmpty()) polylines.add(cur);
        polylines.removeIf(pl -> pl.size() < 2);
        return polylines;
    }

    private static List<List<Pt>> fullWidthPunctuationGlyph(char ch) {
        switch (ch) {
            case '：': return asciiGlyph(':');
            case '，': return asciiGlyph(',');
            case '．': return asciiGlyph('.');
            case '（': return asciiGlyph('(');
            case '）': return asciiGlyph(')');
            default: return Collections.emptyList();
        }
    }

    private static List<Pt> pts(double... coords) {
        List<Pt> list = new ArrayList<>(coords.length / 2);
        for (int i = 0; i + 1 < coords.length; i += 2) list.add(new Pt(coords[i], coords[i + 1]));
        return list;
    }

    private static List<List<Pt>> asciiGlyph(char ch) {
        List<List<Pt>> g = new ArrayList<>();
        switch (ch) {
            case 'A': g.add(pts(0,0, 28,92, 56,0)); g.add(pts(11,34, 45,34)); break;
            case 'B': g.add(pts(0,0, 0,92, 44,92, 54,80, 54,56, 44,46, 0,46)); g.add(pts(44,46, 56,36, 56,10, 44,0, 0,0)); break;
            case 'C': g.add(pts(54,80, 42,92, 12,92, 0,80, 0,12, 12,0, 42,0, 54,12)); break;
            case 'D': g.add(pts(0,0, 0,92, 40,92, 54,78, 54,14, 40,0, 0,0)); break;
            case 'E': g.add(pts(52,92, 0,92, 0,0, 52,0)); g.add(pts(0,46, 38,46)); break;
            case 'F': g.add(pts(52,92, 0,92, 0,0)); g.add(pts(0,46, 38,46)); break;
            case 'G': g.add(pts(54,80, 42,92, 12,92, 0,80, 0,12, 12,0, 42,0, 54,12, 54,42, 30,42)); break;
            case 'H': g.add(pts(0,0, 0,92)); g.add(pts(54,0, 54,92)); g.add(pts(0,46, 54,46)); break;
            case 'I': g.add(pts(8,92, 40,92)); g.add(pts(24,92, 24,0)); g.add(pts(8,0, 40,0)); break;
            case 'J': g.add(pts(14,92, 50,92, 50,14, 36,0, 12,0, 0,14)); break;
            case 'K': g.add(pts(0,0, 0,92)); g.add(pts(52,92, 0,46, 54,0)); break;
            case 'L': g.add(pts(0,92, 0,0, 52,0)); break;
            case 'M': g.add(pts(0,0, 0,92, 28,42, 56,92, 56,0)); break;
            case 'N': g.add(pts(0,0, 0,92, 54,0, 54,92)); break;
            case 'O': g.add(pts(12,92, 42,92, 54,80, 54,12, 42,0, 12,0, 0,12, 0,80, 12,92)); break;
            case 'P': g.add(pts(0,0, 0,92, 44,92, 54,80, 54,54, 44,42, 0,42)); break;
            case 'Q': g.add(pts(12,92, 42,92, 54,80, 54,16, 42,4, 12,4, 0,16, 0,80, 12,92)); g.add(pts(32,26, 56,-4)); break;
            case 'R': g.add(pts(0,0, 0,92, 44,92, 54,80, 54,54, 44,44, 0,44)); g.add(pts(32,44, 54,0)); break;
            case 'S': g.add(pts(52,82, 42,92, 10,92, 0,82, 0,56, 10,46, 44,46, 54,36, 54,10, 44,0, 10,0, 0,10)); break;
            case 'T': g.add(pts(0,92, 56,92)); g.add(pts(28,92, 28,0)); break;
            case 'U': g.add(pts(0,92, 0,12, 12,0, 42,0, 54,12, 54,92)); break;
            case 'V': g.add(pts(0,92, 28,0, 56,92)); break;
            case 'W': g.add(pts(0,92, 14,0, 28,52, 42,0, 56,92)); break;
            case 'X': g.add(pts(0,92, 54,0)); g.add(pts(54,92, 0,0)); break;
            case 'Y': g.add(pts(0,92, 28,46, 56,92)); g.add(pts(28,46, 28,0)); break;
            case 'Z': g.add(pts(0,92, 54,92, 0,0, 54,0)); break;
            case 'a': g.add(pts(48,56, 38,64, 12,64, 0,52, 0,12, 12,0, 38,0, 48,10)); g.add(pts(48,64, 48,0)); break;
            case 'b': g.add(pts(0,92, 0,0)); g.add(pts(0,54, 10,64, 38,64, 48,54, 48,10, 38,0, 10,0, 0,10)); break;
            case 'c': g.add(pts(48,54, 38,64, 12,64, 0,52, 0,12, 12,0, 38,0, 48,10)); break;
            case 'd': g.add(pts(48,92, 48,0)); g.add(pts(48,54, 38,64, 12,64, 0,54, 0,10, 12,0, 38,0, 48,10)); break;
            case 'e': g.add(pts(0,32, 48,32, 48,52, 38,64, 12,64, 0,52, 0,12, 12,0, 40,0, 48,8)); break;
            case 'f': g.add(pts(42,92, 28,92, 18,82, 18,0)); g.add(pts(4,58, 38,58)); break;
            case 'g': g.add(pts(48,54, 38,64, 12,64, 0,54, 0,32, 12,22, 38,22, 48,32, 48,64, 48,-14, 36,-24, 12,-24, 4,-18)); break;
            case 'h': g.add(pts(0,92, 0,0)); g.add(pts(0,46, 12,64, 38,64, 48,52, 48,0)); break;
            case 'i': g.add(pts(22,64, 22,0)); g.add(pts(22,86, 22,90)); break;
            case 'j': g.add(pts(28,64, 28,-14, 18,-24, 6,-24)); g.add(pts(28,86, 28,90)); break;
            case 'k': g.add(pts(0,92, 0,0)); g.add(pts(46,64, 0,26, 48,0)); break;
            case 'l': g.add(pts(18,92, 18,8, 26,0, 34,0)); break;
            case 'm': g.add(pts(0,0, 0,64)); g.add(pts(0,52, 10,64, 22,64, 30,52, 30,0)); g.add(pts(30,52, 40,64, 50,64, 58,52, 58,0)); break;
            case 'n': g.add(pts(0,0, 0,64)); g.add(pts(0,50, 12,64, 38,64, 48,52, 48,0)); break;
            case 'o': g.add(pts(12,64, 38,64, 48,52, 48,12, 38,0, 12,0, 0,12, 0,52, 12,64)); break;
            case 'p': g.add(pts(0,-24, 0,64)); g.add(pts(0,54, 10,64, 38,64, 48,54, 48,10, 38,0, 10,0, 0,10)); break;
            case 'q': g.add(pts(48,-24, 48,64)); g.add(pts(48,54, 38,64, 12,64, 0,54, 0,10, 12,0, 38,0, 48,10)); break;
            case 'r': g.add(pts(0,0, 0,64)); g.add(pts(0,46, 14,64, 36,64, 44,56)); break;
            case 's': g.add(pts(46,56, 38,64, 10,64, 0,56, 0,40, 10,32, 38,32, 48,24, 48,8, 38,0, 10,0, 0,8)); break;
            case 't': g.add(pts(18,86, 18,8, 26,0, 38,0)); g.add(pts(4,64, 34,64)); break;
            case 'u': g.add(pts(0,64, 0,10, 10,0, 36,0, 48,12, 48,64)); break;
            case 'v': g.add(pts(0,64, 24,0, 48,64)); break;
            case 'w': g.add(pts(0,64, 12,0, 24,42, 36,0, 48,64)); break;
            case 'x': g.add(pts(0,64, 46,0)); g.add(pts(46,64, 0,0)); break;
            case 'y': g.add(pts(0,64, 24,0, 48,64, 16,-18, 6,-24)); break;
            case 'z': g.add(pts(0,64, 46,64, 0,0, 46,0)); break;
            case '0': g.add(pts(12,92, 42,92, 54,80, 54,12, 42,0, 12,0, 0,12, 0,80, 12,92)); g.add(pts(6,6, 48,86)); break;
            case '1': g.add(pts(12,74, 28,92, 28,0)); break;
            case '2': g.add(pts(0,80, 12,92, 42,92, 54,80, 54,54, 0,12, 0,0, 56,0)); break;
            case '3': g.add(pts(0,82, 10,92, 42,92, 54,80, 54,56, 42,46, 18,46, 42,46, 54,36, 54,12, 42,0, 10,0, 0,10)); break;
            case '4': g.add(pts(42,0, 42,92, 0,28, 56,28)); break;
            case '5': g.add(pts(52,92, 4,92, 4,50, 42,50, 54,38, 54,12, 42,0, 6,0, 0,8)); break;
            case '6': g.add(pts(50,82, 40,92, 12,92, 0,80, 0,12, 12,0, 42,0, 54,12, 54,38, 42,48, 0,48)); break;
            case '7': g.add(pts(0,92, 54,92, 18,0)); break;
            case '8': g.add(pts(12,46, 0,56, 0,80, 12,92, 42,92, 54,80, 54,56, 42,46, 12,46, 0,36, 0,12, 12,0, 42,0, 54,12, 54,36, 42,46)); break;
            case '9': g.add(pts(54,44, 12,44, 0,54, 0,80, 12,92, 42,92, 54,80, 54,12, 42,0, 10,0, 0,8)); break;
            case '-': g.add(pts(8,46, 46,46)); break;
            case '_': g.add(pts(2,-4, 52,-4)); break;
            case '+': g.add(pts(8,46, 46,46)); g.add(pts(27,27, 27,65)); break;
            case '=': g.add(pts(8,32, 46,32)); g.add(pts(8,58, 46,58)); break;
            case '/': g.add(pts(4,0, 50,92)); break;
            case '(': g.add(pts(34,96, 16,76, 16,16, 34,-4)); break;
            case ')': g.add(pts(16,96, 34,76, 34,16, 16,-4)); break;
            case '.': g.add(pts(14,0, 22,0, 22,8, 14,8, 14,0)); break;
            case ',': g.add(pts(18,6, 24,6, 24,0, 14,-14)); break;
            case ':': g.add(pts(16,12, 24,12, 24,20, 16,20, 16,12)); g.add(pts(16,56, 24,56, 24,64, 16,64, 16,56)); break;
            case '%': g.add(pts(6,0, 48,92)); g.add(pts(6,72, 16,72, 16,84, 6,84, 6,72)); g.add(pts(38,8, 48,8, 48,20, 38,20, 38,8)); break;
            default: break;
        }
        return g;
    }
}
