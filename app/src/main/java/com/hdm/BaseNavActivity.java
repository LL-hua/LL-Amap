package com.hdm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseNavActivity extends Activity {
// 在 BaseNavActivity 的成员变量区域加上
    private AlertDialog roadDialog;
// 在类成员变量区域添加
    private int easterEggClicks = 0;
    protected static final String[] TAB_KEYS = {"earth", "layout", "calc", "realmap"};
    protected static final String[] TAB_LABELS = {"土方", "放样", "正反算", "在线地图"};
    protected static final String[] TAB_ICONS = {"土", "放", "算", "地"};

    private static final int COLOR_PRIMARY = 0xFF1976D2;
    private static final int COLOR_NAV_BG = 0xFFFFFFFF;
    private static final int COLOR_NAV_LINE = 0xFFE0E0E0;
    private static final int COLOR_TAB_ON = 0xFF1976D2;
    private static final int COLOR_TAB_OFF = 0xFF9E9E9E;

    protected FrameLayout contentBox;
    protected TextView tvProj;
    protected TextView tvRoad; // 新增：路线显示

    protected abstract String currentTab();

    protected abstract String pageTitle();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(COLOR_PRIMARY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                window.getDecorView().setSystemUiVisibility(0);
            }
        }

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFF0F2F5);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_PRIMARY);
        header.setPadding(dp(20), dp(14), dp(20), dp(14));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(pageTitle());
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.WHITE);

        // ==========================================
        // 【隐藏彩蛋】连续点击 5 次标题，触发“土方俄罗斯方块”
        // ==========================================
        tvTitle.setOnClickListener(v -> {
            easterEggClicks++;
            if (easterEggClicks >= 5) {
                easterEggClicks = 0; // 重置计数器

                // 创建一个全屏无标题的 Dialog
                android.app.Dialog gameDialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                EarthworkGameView gameView = new EarthworkGameView(this);
                gameDialog.setContentView(gameView);

                // 监听返回键，按返回键直接退出游戏，回到原来的页面
                gameDialog.setOnKeyListener((dialog, keyCode, event) -> {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        gameDialog.dismiss();
                        return true;
                    }
                    return false;
                });

                gameDialog.show();
            }
        });
        // ==========================================

        header.addView(tvTitle);

        // 新增：路线显示按钮
        tvRoad = new TextView(this);
        tvRoad.setTextSize(13);
        tvRoad.setTextColor(0xCCFFFFFF);
        tvRoad.setPadding(dp(12), 0, 0, 0);
        tvRoad.setOnClickListener(v -> showRoadDialog());
        header.addView(tvRoad);

        tvProj = new TextView(this);
        updateHeaderNames();
        tvProj.setTextSize(13);
        tvProj.setTextColor(0xCCFFFFFF);
        tvProj.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        tvProj.setLayoutParams(new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvProj.setOnClickListener(v -> showProjectDialog());
        header.addView(tvProj);

        rootLayout.addView(header);

        contentBox = new FrameLayout(this);
        contentBox.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rootLayout.addView(contentBox);

        rootLayout.addView(buildBottomNav());

        setContentView(rootLayout);

        autoLoadLastProject();
    }

    protected void setContent(View view) {
        contentBox.removeAllViews();
        contentBox.addView(view, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void autoLoadLastProject() {
        SharedPreferences sp = getSharedPreferences("hdm_config", MODE_PRIVATE);
        String lastPath = sp.getString("last_project", null);
        if (lastPath != null) {
            File file = new File(lastPath);
            if (file.exists()) {
                loadProject(file, true);
            }
        }
    }

// ================= 复制 assets 文件到缓存 =================
    protected File copyAssetToCache(String assetName) {
        File file = new File(getFilesDir(), "[内置] " + assetName);
        try (java.io.InputStream is = getAssets().open(assetName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            return file;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    protected void showProjectDialog() {
        // 1. 扫描外部存储 SD 卡
        File sd = Environment.getExternalStorageDirectory();
        File dir = new File(sd, "1hdm");
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles();
        final List<File> xlsxFiles = new ArrayList<>();
        final List<String> xlsxNames = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".xlsx")) {
                    xlsxFiles.add(f);
                    xlsxNames.add(f.getName());
                }
            }
        }

        // 2. 扫描内置 Assets 目录
        try {
            String[] assetFiles = getAssets().list("");
            if (assetFiles != null) {
                for (String name : assetFiles) {
                    if (name.toLowerCase().endsWith(".xlsx")) {
                        File cacheFile = copyAssetToCache(name);
                        if (cacheFile != null) {
                            xlsxFiles.add(cacheFile);
                            xlsxNames.add("[内置] " + name); // 加个前缀，方便用户区分
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. 弹出选择框
        if (xlsxFiles.isEmpty()) {
            Toast.makeText(this, "未找到任何项目 (请确保 1hdm 目录或 assets 中有 .xlsx)", Toast.LENGTH_LONG).show();
            return;
        }

        final String[] items = xlsxNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择项目文件")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        loadProject(xlsxFiles.get(which), false);
                    }
                })
                .show();
    }

    private void loadProject(final File xlsxFile, final boolean isSilent) {
        if (!isSilent) tvProj.setText("加载中...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ProjectData d = XlsxProjectData.Load(xlsxFile.getAbsolutePath());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            MyApp app = (MyApp) getApplication();
                            app.data = d;
                            app.currentProjectName = xlsxFile.getName();
                            app.currentProjectFile = xlsxFile;

                            SharedPreferences sp = getSharedPreferences("hdm_config", MODE_PRIVATE);
                            sp.edit().putString("last_project", xlsxFile.getAbsolutePath()).apply();

                            updateHeaderNames();
                            if (!isSilent) {
                                Toast.makeText(BaseNavActivity.this, "已加载: " + xlsxFile.getName(), Toast.LENGTH_SHORT).show();
                            }
                            onProjectLoaded();
                        }
                    });
                } catch (final Throwable e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateHeaderNames();
                            if (!isSilent) {
                                Toast.makeText(BaseNavActivity.this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    protected void updateHeaderNames() {
        MyApp app = (MyApp) getApplication();
        if (tvProj != null) {
            String projName = (app != null && app.currentProjectName != null) ? app.currentProjectName : "未选择项目";
            tvProj.setText(projName);
        }
        if (tvRoad != null) {
            if (app == null || app.data == null) {
                tvRoad.setText("");
                return;
            }
            String curName = "主线";
            if (app.data.currentPqx != app.data.pqx && app.data.extraRoads != null) {
                for (MapRoadData rd : app.data.extraRoads) {
                    if (rd.pqx == app.data.currentPqx) {
                        curName = rd.name;
                        break;
                    }
                }
            }
            tvRoad.setText("[" + curName + "]");
        }
    }

    protected void onProjectLoaded() {}

    protected void onRoadChanged() {} // 新增：路线切换回调

        private void showRoadDialog() {
        MyApp app = (MyApp) getApplication();
        if (app.data == null) {
            Toast.makeText(this, "请先选择项目", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. 收集所有路线数据
        List<String> names = new ArrayList<>();
        List<double[][]> pqxList = new ArrayList<>();
        List<String> mileageList = new ArrayList<>();

        // 主线
        names.add("主线");
        pqxList.add(app.data.pqx);
        mileageList.add(getMileageString(app.data.pqx));

        // 支线
        if (app.data.extraRoads != null) {
            for (MapRoadData r : app.data.extraRoads) {
                names.add(r.name);
                pqxList.add(r.pqx);
                mileageList.add(getMileageString(r.pqx));
            }
        }

        // 2. 动态构建列表UI
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setBackgroundColor(0xFF1E1E1E); // 深色背景，衬托纯白文字
        listLayout.setPadding(dp(16), dp(8), dp(16), dp(8));

        // 找出当前选中的索引
        int selectedIndex = 0;
        for (int i = 0; i < pqxList.size(); i++) {
            if (pqxList.get(i) == app.data.currentPqx) {
                selectedIndex = i;
                break;
            }
        }

        for (int i = 0; i < names.size(); i++) {
            final int index = i;

            // 每一行的布局
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));

            // 选中项高亮背景（深蓝色）
            if (i == selectedIndex) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF1976D2);
                bg.setCornerRadius(dp(8));
                row.setBackground(bg);
            }

            // 左侧：路线名称（纯白色）
            TextView tvName = new TextView(this);
            tvName.setText(names.get(i));
            tvName.setTextSize(16);
            tvName.setTextColor(Color.WHITE);
            tvName.setTypeface(null, i == selectedIndex ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(lpName);
            row.addView(tvName);

            // 右侧：起止里程（纯白色）
            TextView tvMileage = new TextView(this);
            tvMileage.setText(mileageList.get(i));
            tvMileage.setTextSize(13);
            tvMileage.setTextColor(Color.WHITE);
            row.addView(tvMileage);

            // 点击事件
            row.setOnClickListener(v -> {
                if (app.data.currentPqx != pqxList.get(index)) {
                    app.data.switchRoad(pqxList.get(index));
                    updateHeaderNames();
                    onRoadChanged();
                }
                if (roadDialog != null && roadDialog.isShowing()) {
                    roadDialog.dismiss();
                }
            });

            listLayout.addView(row);

            // 除了最后一行，加一条深色分割线
            if (i < names.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0xFF333333);
                LinearLayout.LayoutParams lpDiv = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
                lpDiv.leftMargin = dp(12);
                lpDiv.rightMargin = dp(12);
                divider.setLayoutParams(lpDiv);
                listLayout.addView(divider);
            }
        }

        // 3. 包装进 ScrollView（防止路线太多撑爆屏幕）
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF1E1E1E);
        scrollView.addView(listLayout);

        // 4. 显示弹窗（使用系统深色主题，保证标题栏和取消按钮也与深色列表协调）
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        builder.setTitle("选择路线");
        builder.setView(scrollView);
        builder.setNegativeButton("取消", null);

        roadDialog = builder.create();
        roadDialog.show();
    }


// 辅助方法：格式化起止里程
    private String getMileageString(double[][] pqx) {
        if (pqx == null || pqx.length == 0) return "--";
        double startK = pqx[0][0];
        int lastIdx = pqx.length - 1;
        double endK = pqx[lastIdx][0] + pqx[lastIdx][4];
        return LL.hua_Num2K(startK) + " ~ " + LL.hua_Num2K(endK);
    }

    private View buildBottomNav() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        View line = new View(this);
        line.setBackgroundColor(COLOR_NAV_LINE);
        wrapper.addView(line, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(COLOR_NAV_BG);
        nav.setPadding(0, dp(6), 0, dp(8));

        String cur = currentTab();

        for (int i = 0; i < 4; i++) {
            final String key = TAB_KEYS[i];
            boolean active = key.equals(cur);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(4));
            item.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView icon = new TextView(this);
            icon.setText(TAB_ICONS[i]);
            icon.setTextSize(20);
            icon.setGravity(Gravity.CENTER);
            icon.setTextColor(active ? COLOR_TAB_ON : COLOR_TAB_OFF);
            item.addView(icon);

            TextView label = new TextView(this);
            label.setText(TAB_LABELS[i]);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(2), 0, 0);
            label.setTextColor(active ? COLOR_TAB_ON : COLOR_TAB_OFF);
            item.addView(label);

            if (active) {
                icon.setTypeface(null, android.graphics.Typeface.BOLD);
                label.setTypeface(null, android.graphics.Typeface.BOLD);
            }

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goTab(key);
                }
            });

            nav.addView(item);
        }

        wrapper.addView(nav);
        return wrapper;
    }

    protected void goTab(String key) {
        if (key.equals(currentTab())) return;

        Class<?> target = null;
        if ("earth".equals(key)) target = EarthworkActivity.class;
        if ("layout".equals(key)) target = LayoutActivity.class;
        if ("calc".equals(key)) target = CalcActivity.class;
        if ("realmap".equals(key)) target = RealMapActivity.class;

        if (target == null) return;

        MyApp app = (MyApp) getApplication();

        if (!"realmap".equals(key) && (app == null || app.data == null)) {
            Toast.makeText(this, "请先点击右上角选择项目", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent it = new Intent(this, target);
        it.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(it);
        finish();
    }

    protected int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}