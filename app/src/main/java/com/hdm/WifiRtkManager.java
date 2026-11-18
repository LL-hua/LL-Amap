package com.hdm;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;

public class WifiRtkManager {
    public interface RtkCallback {
        void onRtkData(double lat, double lon, double alt, int fixQuality);
        void onRtkStatus(String status);
    }

    private Socket socket;
    private BufferedReader reader;
    private boolean isReading = false;
    private RtkCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void setCallback(RtkCallback callback) {
        this.callback = callback;
    }

    public void connect(String ip, int port) {
        disconnect();
        new Thread(() -> {
            try {
                sendStatus("正在连接 WiFi...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 3000); // 3秒超时
                socket.setSoTimeout(5000); 
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                isReading = true;
                sendStatus("WiFi已连接");
                readLoop();
            } catch (Exception e) {
                sendStatus("WiFi连接失败");
                e.printStackTrace();
            }
        }).start();
    }

    private void readLoop() {
        try {
            String line;
            while (isReading && (line = reader.readLine()) != null) {
                if (line.startsWith("$GNGGA") || line.startsWith("$GPGGA")) {
                    parseGGA(line); // 解析逻辑与蓝牙版完全一致
                }
            }
        } catch (IOException e) {
            if (isReading) sendStatus("WiFi连接断开");
        }
    }

    private void parseGGA(String gga) {
        try {
            String[] parts = gga.split(",");
            if (parts.length > 9) {
                String latStr = parts[2];
                String latDir = parts[3];
                String lonStr = parts[4];
                String lonDir = parts[5];
                int fixQuality = Integer.parseInt(parts[6].isEmpty() ? "0" : parts[6]);
                double alt = Double.parseDouble(parts[9].isEmpty() ? "0" : parts[9]);

                if (latStr.isEmpty() || lonStr.isEmpty()) return;

                double lat = Double.parseDouble(latStr.substring(0, 2)) + Double.parseDouble(latStr.substring(2)) / 60.0;
                if (latDir.equals("S")) lat = -lat;

                double lon = Double.parseDouble(lonStr.substring(0, 3)) + Double.parseDouble(lonStr.substring(3)) / 60.0;
                if (lonDir.equals("W")) lon = -lon;

                final double finalLat = lat;
                final double finalLon = lon;
                final double finalAlt = alt;
                final int finalFixQuality = fixQuality;

                if (callback != null) {
                    mainHandler.post(() -> callback.onRtkData(finalLat, finalLon, finalAlt, finalFixQuality));
                }
            }
        } catch (Exception ignored) {}
    }

    private void sendStatus(String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onRtkStatus(msg));
        }
    }

    public void disconnect() {
        isReading = false;
        try {
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        reader = null;
        socket = null;
    }
}