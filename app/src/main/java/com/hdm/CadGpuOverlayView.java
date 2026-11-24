package com.hdm;

import android.content.Context;
import android.opengl.*;
import android.util.AttributeSet;
import com.cadapp.HzShxFontTool;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class CadGpuOverlayView extends GLSurfaceView {
    public static class LayerItem {
        public final int id; public final String name; public int count; public boolean visible = true;
        public LayerItem(int id, String name, int count) { this.id = id; this.name = name; this.count = count; }
    }
    private final CadRenderer renderer;

    public CadGpuOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        renderer = new CadRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void updateViewport(double lat, double lng, float zoom) { renderer.updateViewport(lat, lng, zoom); requestRender(); }

    public void setCadBinary(byte[] bin, MyApp app, HzShxFontTool shxTool) {
        if (bin == null || bin.length < 44 || app == null || app.data == null) renderer.clearData();
        else { renderer.shxFont = shxTool; renderer.buildFromBinary(bin, app); }
        requestRender();
    }

    public LayerItem pickLayerAt(float sx, float sy) { return renderer.pickLayerAt(sx, sy); }
    public List<LayerItem> getLayerItems() { return renderer.layerItems; }

    public void setLayerItemVisible(int id, boolean vis) {
        if (id >= 0 && id < renderer.layerVis.length) {
            renderer.layerVis[id] = vis;
            for (LayerItem it : renderer.layerItems) if (it.id == id) it.visible = vis;
            requestRender();
        }
    }

    public void setCadVisibility(boolean line, boolean text) { renderer.showLines = line; renderer.showTexts = text; requestRender(); }
    public boolean isShowCadLine() { return renderer.showLines; }
    public boolean isShowCadCircle() { return renderer.showTexts; }
    public boolean isShowCadText() { return renderer.showTexts; }
    public boolean hasCadData() { return renderer.hasData(); }
    public void clearCad() { renderer.clearData(); requestRender(); }

    public void updateTextParams(float ts, float es, float ps) { renderer.textScaleMult = ts; renderer.needsRebuild = true; requestRender(); }
    public float getEnScale() { return renderer.textScaleMult; }
    public float getEnSpacing() { return 1.0f; }
    public float getPunctSpacing() { return 1.0f; }

    private static class DrawBatch {
        int typeId, layerId, startVert, vertCount;
        DrawBatch(int t, int l, int sv, int vc) { typeId = t; layerId = l; startVert = sv; vertCount = vc; }
    }

    private static class CadRenderer implements GLSurfaceView.Renderer {
        private int program, aPosition, aColor, uMatrix, vboId = 0;
        private volatile FloatBuffer pendingBuf = null, activeBuf = null;
        private volatile int pendingVerts = 0, vertexCount = 0;
        private volatile boolean needReupload = false;
        private volatile List<DrawBatch> pendingBatches = null;
        private final List<DrawBatch> batches = new ArrayList<>();

        public final List<LayerItem> layerItems = new ArrayList<>();
        public volatile boolean[] layerVis = new boolean[0];
        public volatile boolean[] typeVis  = new boolean[0];
        public volatile boolean showLines = true, showTexts = true;

        private final float[] mvpMatrix = new float[16];
        private double centerLat = -4.8, centerLng = 32.5;
        private float zoom = 15f;
        public float scaleFactor = 2.0f;
        private int viewW = 1, viewH = 1;

        public byte[] rawBin = null;
        public MyApp rawApp = null;
        public volatile HzShxFontTool shxFont = null;
        public volatile float textScaleMult = 1.0f;
        public volatile boolean needsRebuild = false;

        private double originMercX = 0, originMercY = 0;

        public boolean hasData() { return vertexCount > 0 || rawBin != null; }
        public void updateViewport(double lat, double lng, float z) { centerLat = lat; centerLng = lng; zoom = z; }

        public void clearData() {
            vertexCount = 0; pendingVerts = 0; activeBuf = null; pendingBuf = null;
            rawBin = null; rawApp = null; batches.clear(); layerItems.clear();
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(vs, "uniform mat4 u_Matrix; attribute vec4 a_Position; attribute vec4 a_Color; varying vec4 v_Color; void main() { gl_Position = u_Matrix * a_Position; v_Color = a_Color; }");
            GLES20.glCompileShader(vs);
            int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(fs, "precision mediump float; varying vec4 v_Color; void main() { gl_FragColor = v_Color; }");
            GLES20.glCompileShader(fs);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
            aPosition = GLES20.glGetAttribLocation(program, "a_Position");
            aColor = GLES20.glGetAttribLocation(program, "a_Color");
            uMatrix = GLES20.glGetUniformLocation(program, "u_Matrix");
            int[] vbos = new int[1]; GLES20.glGenBuffers(1, vbos, 0); vboId = vbos[0];
            if (activeBuf != null) needReupload = true;
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) { GLES20.glViewport(0, 0, w, h); viewW = w; viewH = h; }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (needsRebuild && rawBin != null && rawApp != null) { buildFromBinary(rawBin, rawApp); needsRebuild = false; }
            if (pendingBuf != null) {
                activeBuf = pendingBuf; vertexCount = pendingVerts;
                if (pendingBatches != null) { batches.clear(); batches.addAll(pendingBatches); pendingBatches = null; }
                pendingBuf = null; needReupload = true;
            }
            if (needReupload && activeBuf != null && vertexCount > 0) {
                activeBuf.position(0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexCount * 24, activeBuf, GLES20.GL_STATIC_DRAW);
                needReupload = false;
            }
            if (vertexCount == 0) return;

            int tileZoom = (int) Math.floor(zoom);
            double worldSize = 256.0 * Math.pow(2.0, tileZoom);
            double sinLat = Math.sin(centerLat * Math.PI / 180.0);
            double cwX = (centerLng + 180.0) / 360.0 * worldSize;
            double cwY = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * worldSize;
            float totalScale = scaleFactor * (float) Math.pow(2.0, zoom - tileZoom);
            double startX = cwX - (viewW / 2.0) / totalScale;
            double startY = cwY - (viewH / 2.0) / totalScale;

            float[] proj = new float[16], trans = new float[16], sc = new float[16], tmp = new float[16];
            Matrix.orthoM(proj, 0, 0f, viewW, viewH, 0f, -1f, 1f);
            Matrix.setIdentityM(trans, 0);
            Matrix.translateM(trans, 0, (float)(-startX + originMercX * worldSize), (float)(-startY + originMercY * worldSize), 0f);
            Matrix.scaleM(trans, 0, (float)worldSize, (float)worldSize, 1f);
            Matrix.setIdentityM(sc, 0); Matrix.scaleM(sc, 0, totalScale, totalScale, 1f);
            Matrix.multiplyMM(tmp, 0, sc, 0, trans, 0);
            Matrix.multiplyMM(mvpMatrix, 0, proj, 0, tmp, 0);

            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(uMatrix, 1, false, mvpMatrix, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 24, 0);
            GLES20.glEnableVertexAttribArray(aColor);
            GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 24, 8);

            for (DrawBatch b : batches) {
                if (b.vertCount <= 0) continue;
                if (b.typeId == 0 && !showTexts) continue;
                if (b.typeId != 0 && !showLines) continue;
                if (b.typeId < typeVis.length  && !typeVis[b.typeId])  continue;
                if (b.layerId < layerVis.length && !layerVis[b.layerId]) continue;
                GLES20.glDrawArrays(GLES20.GL_LINES, b.startVert, b.vertCount);
            }
        }

        /** 与 CadGLSurfaceView.rebuildFromBin 完全一致的桶/文字/计数逻辑，仅顶点写 VBO 时走反投影 */
                public LayerItem pickLayerAt(float sx, float sy) {
            if (activeBuf == null || vertexCount == 0 || viewW <= 0 || viewH <= 0) return null;
            int tileZoom = (int) Math.floor(zoom);
            double worldSize = 256.0 * Math.pow(2.0, tileZoom);
            double sinLat = Math.sin(centerLat * Math.PI / 180.0);
            double cwX = (centerLng + 180.0) / 360.0 * worldSize;
            double cwY = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * worldSize;
            float totalScale = scaleFactor * (float) Math.pow(2.0, zoom - tileZoom);
            double startX = cwX - (viewW / 2.0) / totalScale;
            double startY = cwY - (viewH / 2.0) / totalScale;

            double bestDistSq = 36.0 * 36.0;
            int bestLayerId = -1;

            for (DrawBatch b : batches) {
                if (b.vertCount <= 0 || b.typeId == 0) continue;
                if (b.typeId < typeVis.length && !typeVis[b.typeId]) continue;
                if (b.layerId < layerVis.length && !layerVis[b.layerId]) continue;
                int endV = b.startVert + b.vertCount;
                for (int v = b.startVert; v + 1 < endV; v += 2) {
                    int p1 = v * 6, p2 = (v + 1) * 6;
                    float x1 = (float)(((originMercX + activeBuf.get(p1)) * worldSize - startX) * totalScale);
                    float y1 = (float)(((originMercY + activeBuf.get(p1 + 1)) * worldSize - startY) * totalScale);
                    float x2 = (float)(((originMercX + activeBuf.get(p2)) * worldSize - startX) * totalScale);
                    float y2 = (float)(((originMercY + activeBuf.get(p2 + 1)) * worldSize - startY) * totalScale);

                    float dx = x2 - x1, dy = y2 - y1;
                    float lenSq = dx * dx + dy * dy;
                    float t = (lenSq > 1e-6f) ? Math.max(0f, Math.min(1f, ((sx - x1) * dx + (sy - y1) * dy) / lenSq)) : 0f;
                    float px = x1 + t * dx, py = y1 + t * dy;
                    float distSq = (sx - px) * (sx - px) + (sy - py) * (sy - py);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestLayerId = b.layerId;
                    }
                }
            }
            if (bestLayerId >= 0) {
                for (LayerItem it : layerItems) if (it.id == bestLayerId) return it;
            }
            return null;
        }

        public void buildFromBinary(byte[] bin, MyApp app) {
            this.rawBin = bin; this.rawApp = app;
            try {
                ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
                double gcx = bb.getDouble(), gcy = bb.getDouble();
                int strokeCount = bb.getInt(), labelCount = bb.getInt();
                float minX = bb.getFloat(), minY = bb.getFloat(), maxX = bb.getFloat(), maxY = bb.getFloat();
                int dictLen = bb.getInt();
                int typeNum = bb.get() & 0xFF;
                List<String> tNames = new ArrayList<>();
                for (int i = 0; i < typeNum; i++) { byte[] nb = new byte[bb.get() & 0xFF]; bb.get(nb); tNames.add(new String(nb, StandardCharsets.UTF_8)); }
                int layerNum = bb.getShort() & 0xFFFF;
                List<String> lNames = new ArrayList<>();
                for (int i = 0; i < layerNum; i++) { byte[] nb = new byte[bb.get() & 0xFF]; bb.get(nb); lNames.add(new String(nb, StandardCharsets.UTF_8)); }

                // —— 反投影：地图锚点（与本地渲染的唯一差别） ——
                double midX = gcx + (minX + maxX) * 0.5, midY = gcy + (minY + maxY) * 0.5;
                double[] lbO = Projection.xy2Gps(midY, midX, app.data.projection, app.data.controlLonLat, app.data.controlXy, app.data.lonCenter);
                if (lbO != null && lbO.length >= 2 && lbO[0] != 0) {
                    originMercX = (lbO[0] + 180.0) / 360.0;
                    double latC = Math.max(-85.05112878, Math.min(85.05112878, lbO[1]));
                    originMercY = 0.5 - Math.log(Math.tan((90.0 + latC) * Math.PI / 360.0)) / (2.0 * Math.PI);
                }

                int strokesStart = 44 + dictLen;
                int[] tCnt = new int[typeNum], lCnt = new int[layerNum];
                Map<Integer, Integer> bucketCnt = new LinkedHashMap<>();
                for (int i = 0; i < strokeCount; i++) {
                    int off = strokesStart + i * 23;
                    int tid = bin[off + 20] & 0xFF, lid = (bin[off + 21] & 0xFF) | ((bin[off + 22] & 0xFF) << 8);
                    if (tid < typeNum && lid < layerNum) {
                        tCnt[tid]++; lCnt[lid]++;
                        int k = (tid << 16) | lid;
                        bucketCnt.put(k, bucketCnt.getOrDefault(k, 0) + 1);
                    }
                }

                List<float[]> textStrokes = new ArrayList<>();
                List<Integer> textColors = new ArrayList<>(), textLayers = new ArrayList<>();
                int labelOffset = strokesStart + strokeCount * 23;
                if (labelCount > 0 && labelOffset < bin.length && shxFont != null) {
                    bb.position(labelOffset);
                    for (int i = 0; i < labelCount && bb.remaining() >= 25; i++) {
                        float lx = bb.getFloat(), ly = bb.getFloat(), lh = bb.getFloat(), rot = bb.getFloat();
                        int col = bb.getInt(), anchor = bb.get() & 0xFF, lid = bb.getShort() & 0xFFFF, tlen = bb.getShort() & 0xFFFF;
                        if (bb.remaining() < tlen) break;
                        byte[] tb = new byte[tlen]; bb.get(tb);
                        if (lid < layerNum) {
                            int before = textStrokes.size();
                            buildShxStrokes(new String(tb, StandardCharsets.UTF_8), lx, ly, lh, rot, anchor, col, textStrokes, textColors);
                            int added = textStrokes.size() - before;
                            for (int k = 0; k < added; k++) textLayers.add(lid);
                            if (added > 0 && typeNum > 0) tCnt[0] += added;
                            lCnt[lid] += added;
                            bucketCnt.put(lid, bucketCnt.getOrDefault(lid, 0) + added);
                        }
                    }
                }

                Map<Integer, Integer> writePos = new HashMap<>();
                List<DrawBatch> newBatches = new ArrayList<>();
                int curStroke = 0;
                for (Map.Entry<Integer, Integer> en : bucketCnt.entrySet()) {
                    int k = en.getKey(), cnt = en.getValue();
                    writePos.put(k, curStroke);
                    newBatches.add(new DrawBatch(k >>> 16, k & 0xFFFF, curStroke * 2, cnt * 2));
                    curStroke += cnt;
                }
                int totalVerts = curStroke * 2;
                FloatBuffer fb = ByteBuffer.allocateDirect(Math.max(24, totalVerts * 24)).order(ByteOrder.nativeOrder()).asFloatBuffer();

                for (int i = 0; i < textStrokes.size(); i++) {
                    float[] st = textStrokes.get(i);
                    int col = textColors.get(i), k = textLayers.get(i);
                    Integer w = writePos.get(k);
                    if (w == null) continue;
                    int idx = w; writePos.put(k, idx + 1);
                    putMercSeg(fb, idx * 12, gcx + st[0], gcy + st[1], gcx + st[2], gcy + st[3], col, app);
                }

                bb.position(strokesStart);
                for (int i = 0; i < strokeCount; i++) {
                    float x1 = bb.getFloat(), y1 = bb.getFloat(), x2 = bb.getFloat(), y2 = bb.getFloat();
                    int col = bb.getInt(), tid = bb.get() & 0xFF, lid = bb.getShort() & 0xFFFF;
                    if (tid >= typeNum || lid >= layerNum) continue;
                    int k = (tid << 16) | lid;
                    Integer w = writePos.get(k);
                    if (w == null) continue;
                    int idx = w; writePos.put(k, idx + 1);
                    putMercSeg(fb, idx * 12, gcx + x1, gcy + y1, gcx + x2, gcy + y2, col, app);
                }

                fb.position(0);
                pendingBuf = fb; pendingVerts = totalVerts; pendingBatches = newBatches;

                if (layerVis.length != layerNum) { boolean[] nv = new boolean[layerNum]; Arrays.fill(nv, true); layerVis = nv; }
                layerItems.clear();
                for (int i = 0; i < layerNum; i++) if (lCnt[i] > 0) {
                    LayerItem it = new LayerItem(i, lNames.get(i), lCnt[i]);
                    it.visible = i < layerVis.length ? layerVis[i] : true;
                    layerItems.add(it);
                }
                if (typeVis.length != typeNum) { boolean[] nv = new boolean[typeNum]; Arrays.fill(nv, true); typeVis = nv; }
            } catch (Exception e) { e.printStackTrace(); }
        }

        private void buildShxStrokes(String txt, float x, float y, float h, float rot, int anchor, int col,
                                     List<float[]> out, List<Integer> cols) {
            if (txt == null || txt.isEmpty() || shxFont == null) return;
            if (Math.abs(rot) > 6.2832f) rot = (float) Math.toRadians(rot);
            double scale = (h / 92.0) * textScaleMult;
            HzShxFontTool.LayoutData layout = shxFont.layoutLocal(txt, scale);
            double ox = (anchor == 1) ? -layout.width() / 2.0 : (anchor == 2) ? -layout.width() : 0.0;
            double cos = Math.cos(rot), sin = Math.sin(rot);
            for (List<HzShxFontTool.Pt> pl : layout.localPolylines) {
                for (int i = 0; i + 1 < pl.size(); i++) {
                    HzShxFontTool.Pt p1 = pl.get(i), p2 = pl.get(i + 1);
                    double lx1 = ox + p1.x, ly1 = p1.y, lx2 = ox + p2.x, ly2 = p2.y;
                    out.add(new float[]{
                        (float)(x + lx1 * cos - ly1 * sin), (float)(y + lx1 * sin + ly1 * cos),
                        (float)(x + lx2 * cos - ly2 * sin), (float)(y + lx2 * sin + ly2 * cos)
                    });
                    cols.add(col);
                }
            }
        }

        private void putMercSeg(FloatBuffer fb, int p, double cx1, double cy1, double cx2, double cy2, int col, MyApp app) {
            double[] lb1 = Projection.xy2Gps(cy1, cx1, app.data.projection, app.data.controlLonLat, app.data.controlXy, app.data.lonCenter);
            double[] lb2 = Projection.xy2Gps(cy2, cx2, app.data.projection, app.data.controlLonLat, app.data.controlXy, app.data.lonCenter);
            float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f;
            if (lb1 == null || lb2 == null || lb1.length < 2 || lb2.length < 2 || lb1[0] == 0 || lb2[0] == 0) {
                for (int i = 0; i < 12; i++) fb.put(p + i, 0f);
                return;
            }
            float mx1 = (float)((lb1[0] + 180.0) / 360.0 - originMercX);
            float my1 = (float)(0.5 - Math.log(Math.tan((90.0 + Math.max(-85.05112878, Math.min(85.05112878, lb1[1]))) * Math.PI / 360.0)) / (2.0 * Math.PI) - originMercY);
            float mx2 = (float)((lb2[0] + 180.0) / 360.0 - originMercX);
            float my2 = (float)(0.5 - Math.log(Math.tan((90.0 + Math.max(-85.05112878, Math.min(85.05112878, lb2[1]))) * Math.PI / 360.0)) / (2.0 * Math.PI) - originMercY);
            fb.put(p,     mx1); fb.put(p + 1, my1); fb.put(p + 2, r); fb.put(p + 3, g); fb.put(p + 4, b); fb.put(p + 5, 1f);
            fb.put(p + 6, mx2); fb.put(p + 7, my2); fb.put(p + 8, r); fb.put(p + 9, g); fb.put(p + 10, b); fb.put(p + 11, 1f);
        }
    }
}