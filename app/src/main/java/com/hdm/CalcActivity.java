package com.hdm;

import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;

public class CalcActivity extends BaseNavActivity {
    private MyApp app;
    private RadioGroup rgMode;
    private RadioButton rbForward, rbReverse, rbGpsToXy, rbXyToGps;
    private LinearLayout boxForward, boxReverse, boxGps, resultBox, content;
    private EditText etK, etB, etX, etY, etLat, etLon;

    @Override protected String currentTab() { return "calc"; }
    @Override protected String pageTitle() { return "正反算"; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); app = (MyApp) getApplication();
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(16), dp(20), dp(16));
        ScrollView sv = new ScrollView(this); sv.addView(content); setContent(sv); initUI();
    }
    @Override protected void onProjectLoaded() { initUI(); }

    private EditText makeNumInput(String hint) {
        EditText et = new EditText(this); et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return et;
    }
    private LinearLayout makeRow(String label, EditText et) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this); tv.setText(label); tv.setWidth(dp(115)); row.addView(tv); row.addView(et);
        return row;
    }

    private void initUI() {
        content.removeAllViews();
        if (app.data == null) {
            TextView tv = new TextView(this); tv.setText("请点击右上角选择项目文件"); tv.setTextSize(16); tv.setGravity(Gravity.CENTER); tv.setPadding(0, dp(80), 0, 0);
            content.addView(tv); return;
        }
        rgMode = new RadioGroup(this); rgMode.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        rbForward = new RadioButton(this); rbForward.setText("线路正算"); rbForward.setId(1); rbForward.setChecked(true);
        rbReverse = new RadioButton(this); rbReverse.setText("线路反算"); rbReverse.setId(2);
        rbGpsToXy = new RadioButton(this); rbGpsToXy.setText("投影正算"); rbGpsToXy.setId(3);
        rbXyToGps = new RadioButton(this); rbXyToGps.setText("投影反算"); rbXyToGps.setId(4);
        r1.addView(rbForward, new LinearLayout.LayoutParams(0, -2, 1f)); r1.addView(rbReverse, new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(rbGpsToXy, new LinearLayout.LayoutParams(0, -2, 1f)); r2.addView(rbXyToGps, new LinearLayout.LayoutParams(0, -2, 1f));
        rgMode.addView(r1); rgMode.addView(r2); rgMode.setPadding(0, 0, 0, dp(8)); content.addView(rgMode);

        boxForward = new LinearLayout(this); boxForward.setOrientation(LinearLayout.VERTICAL);
        etK = makeNumInput("米（如 1250.5）"); etB = makeNumInput("米（左负右正）");
        boxForward.addView(makeRow("桩号 K: ", etK)); boxForward.addView(makeRow("偏距 B: ", etB)); content.addView(boxForward);

        boxReverse = new LinearLayout(this); boxReverse.setOrientation(LinearLayout.VERTICAL); boxReverse.setVisibility(View.GONE);
        etX = makeNumInput("北坐标 X (N)"); etY = makeNumInput("东坐标 Y (E)");
        boxReverse.addView(makeRow("施工 X: ", etX)); boxReverse.addView(makeRow("施工 Y: ", etY)); content.addView(boxReverse);

        boxGps = new LinearLayout(this); boxGps.setOrientation(LinearLayout.VERTICAL); boxGps.setVisibility(View.GONE);
        etLat = makeNumInput("十进制度（如 22.7781）"); etLon = makeNumInput("十进制度（如 113.5081）");
        boxGps.addView(makeRow("纬度 B: ", etLat)); boxGps.addView(makeRow("经度 L: ", etLon)); content.addView(boxGps);

        Button btnCalc = new Button(this); btnCalc.setText("开始计算"); content.addView(btnCalc);
        resultBox = new LinearLayout(this); resultBox.setOrientation(LinearLayout.VERTICAL); resultBox.setPadding(10, 12, 10, 12); content.addView(resultBox);

        View.OnClickListener sw = v -> {
            int id = v.getId();
            rbForward.setChecked(id == 1); rbReverse.setChecked(id == 2); rbGpsToXy.setChecked(id == 3); rbXyToGps.setChecked(id == 4);
            boxForward.setVisibility(id == 1 ? View.VISIBLE : View.GONE);
            boxReverse.setVisibility((id == 2 || id == 4) ? View.VISIBLE : View.GONE);
            boxGps.setVisibility(id == 3 ? View.VISIBLE : View.GONE);
            resultBox.removeAllViews();
        };
        rbForward.setOnClickListener(sw); rbReverse.setOnClickListener(sw); rbGpsToXy.setOnClickListener(sw); rbXyToGps.setOnClickListener(sw);
        btnCalc.setOnClickListener(v -> {
            if (rbForward.isChecked()) doForward(); else if (rbReverse.isChecked()) doReverse();
            else if (rbGpsToXy.isChecked()) doGpsToXy(); else if (rbXyToGps.isChecked()) doXyToGps();
        });
    }
    private void doForward() {
        double k, b;
        try { k = Double.parseDouble(etK.getText().toString().trim()); b = Double.parseDouble(etB.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "输入格式错误", Toast.LENGTH_SHORT).show(); return; }
        double startK = app.data.currentPqx[0][0]; int lastIdx = app.data.currentPqx.length - 1;
        double endK = app.data.currentPqx[lastIdx][0] + app.data.currentPqx[lastIdx][4];
        if (k < startK || k > endK) { clearResult(); addResultLine("桩号超出范围 (" + String.format("%.2f~%.2f", startK, endK) + ")", null, 0xFFDD0000); return; }
        double[] xy = LL.hua_Dantiaoxianludange(app.data.currentPqx, k, b, 90);
        double h = LL.hua_H(app.data.sqx, k);
        clearResult();
        addResultLine("桩号: " + LL.hua_Num2K(k), null, 0xFF333333);
        addResultLine("X = " + String.format("%.3f", xy[0]), String.format("%.3f", xy[0]), 0xFF0078D4);
        addResultLine("Y = " + String.format("%.3f", xy[1]), String.format("%.3f", xy[1]), 0xFF0078D4);
        addResultLine("中桩高程 H = " + String.format("%.3f", h), String.format("%.3f", h), 0xFF0078D4);
    }

    private void doReverse() {
        double x, y;
        try { x = Double.parseDouble(etX.getText().toString().trim()); y = Double.parseDouble(etY.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "输入格式错误", Toast.LENGTH_SHORT).show(); return; }
        double[] kb = LL.hua_Fs(app.data.currentPqx, x, y); double k = kb[0], b = kb[1];
        clearResult();
        if (k < 0) { addResultLine("坐标超出线路范围", null, 0xFFDD0000); return; }
        double h = LL.hua_H(app.data.sqx, k);
        addResultLine("桩号 = " + LL.hua_Num2K(k), String.format("%.3f", k), 0xFF0078D4);
        addResultLine("偏距 = " + String.format("%.3f", b) + " m（" + (b < 0 ? "左" : "右") + "）", String.format("%.3f", b), 0xFF0078D4);
        addResultLine("中桩高程 H = " + String.format("%.3f", h), String.format("%.3f", h), 0xFF0078D4);
    }

    private void doGpsToXy() {
        double lat, lon;
        try { lat = Double.parseDouble(etLat.getText().toString().trim()); lon = Double.parseDouble(etLon.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "经纬度格式错误", Toast.LENGTH_SHORT).show(); return; }
        double[] xy = Projection.gps2Xy(lon, lat, app.data.projection, app.data.controlLonLat, app.data.controlXy, app.data.lonCenter);
        clearResult();
        if (xy == null || xy.length < 2) { addResultLine("投影正算失败，请检查控制点配置", null, 0xFFDD0000); return; }
        addResultLine("投影类型: " + app.data.projection, null, 0xFF555555);
        addResultLine("施工 X = " + String.format("%.3f", xy[0]), String.format("%.3f", xy[0]), 0xFF0078D4);
        addResultLine("施工 Y = " + String.format("%.3f", xy[1]), String.format("%.3f", xy[1]), 0xFF0078D4);
        double[] kb = LL.hua_Fs(app.data.currentPqx, xy[0], xy[1]);
        if (kb[0] >= 0) {
            addResultLine("对应桩号: " + LL.hua_Num2K(kb[0]), String.format("%.3f", kb[0]), 0xFF0078D4);
            addResultLine("对应偏距: " + String.format("%.3f", kb[1]) + " m（" + (kb[1] < 0 ? "左" : "右") + "）", String.format("%.3f", kb[1]), 0xFF0078D4);
        }
    }

    private void doXyToGps() {
        double x, y;
        try { x = Double.parseDouble(etX.getText().toString().trim()); y = Double.parseDouble(etY.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "坐标格式错误", Toast.LENGTH_SHORT).show(); return; }
        double[] lb = Projection.xy2Gps(x, y, app.data.projection, app.data.controlLonLat, app.data.controlXy, app.data.lonCenter);
        clearResult();
        if (lb == null || lb.length < 2 || lb[0] == 0) { addResultLine("投影反算失败，请检查控制点配置", null, 0xFFDD0000); return; }
        double lon = lb[0], lat = lb[1];
        addResultLine("投影类型: " + app.data.projection, null, 0xFF555555);
        addResultLine("纬度 B = " + String.format("%.8f°", lat), String.format("%.8f", lat), 0xFF0078D4);
        addResultLine("经度 L = " + String.format("%.8f°", lon), String.format("%.8f", lon), 0xFF0078D4);
        addResultLine("度分秒 B: " + toDms(lat), toDms(lat), 0xFF0078D4);
        addResultLine("度分秒 L: " + toDms(lon), toDms(lon), 0xFF0078D4);
    }

    private String toDms(double deg) {
        String sign = deg < 0 ? "-" : ""; double v = Math.abs(deg); int d = (int) v;
        double rem = (v - d) * 60.0; int m = (int) rem; double s = (rem - m) * 60.0;
        return String.format("%s%d°%02d′%.4f″", sign, d, m, s);
    }

    @Override protected void onRoadChanged() {
        if (rbForward != null && rbForward.isChecked() && !etK.getText().toString().trim().isEmpty() && !etB.getText().toString().trim().isEmpty()) doForward();
        else if (rbReverse != null && rbReverse.isChecked() && !etX.getText().toString().trim().isEmpty() && !etY.getText().toString().trim().isEmpty()) doReverse();
        else if (rbGpsToXy != null && rbGpsToXy.isChecked() && !etLat.getText().toString().trim().isEmpty() && !etLon.getText().toString().trim().isEmpty()) doGpsToXy();
    }

    private void addResultLine(String display, final String copyValue, int color) {
        TextView tv = new TextView(this); tv.setText(display); tv.setTextSize(16); tv.setTextColor(color); tv.setPadding(15, 12, 15, 12);
        resultBox.addView(tv);
        if (copyValue != null) {
            tv.setBackgroundColor(0xFFF0F8FF);
            tv.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("hdm", copyValue));
                Toast.makeText(CalcActivity.this, "已复制: " + copyValue, Toast.LENGTH_SHORT).show();
            });
        }
    }
    private void clearResult() { resultBox.removeAllViews(); }
}
