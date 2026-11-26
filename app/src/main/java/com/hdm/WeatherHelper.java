package com.hdm;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class WeatherHelper {
    public static class WeatherInfo {
        public String badgeText;
        public String detailText;
    }

    private static String codeToDesc(int c) {
        if (c == 0) return "☀️ 晴朗";
        if (c <= 3) return "⛅ 多云";
        if (c == 45 || c == 48) return "🌫 雾霾";
        if (c <= 57) return "🌦 毛毛雨";
        if (c <= 67) return "🌧 降雨";
        if (c <= 82) return "🌦 阵雨";
        if (c >= 95) return "⛈ 雷阵雨";
        return "☁️ 阴天";
    }

    private static String fetchLocationName(double lat, double lon) {
        try {
            String h = "api." + "bigdatacloud.net";
            String u = String.format(Locale.US, "https://" + h + "/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=zh", lat, lon);
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            br.close();
            JSONObject j = new JSONObject(sb.toString());
            String loc = j.optString("locality", "");
            String city = j.optString("city", "");
            String sub = j.optString("principalSubdivision", "");
            String country = j.optString("countryName", "");
            List<String> parts = new ArrayList<>();
            for (String item : new String[]{loc, city, sub, country}) {
                if (item != null && !item.trim().isEmpty() && !parts.contains(item.trim())) parts.add(item.trim());
            }
            if (!parts.isEmpty()) return String.join(" · ", parts);
        } catch (Exception ignored) {}
        return "未知区域";
    }

    public static WeatherInfo fetch(double lat, double lon) throws Exception {
        String u = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m&hourly=temperature_2m,precipitation_probability,precipitation,weather_code&forecast_days=1&timezone=auto",
            lat, lon);
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject root = new JSONObject(sb.toString());
        JSONObject cur = root.getJSONObject("current");
        double temp = cur.getDouble("temperature_2m");
        int hum = cur.getInt("relative_humidity_2m");
        int code = cur.getInt("weather_code");
        double wind = cur.getDouble("wind_speed_10m");
        int wdir = cur.getInt("wind_direction_10m");

        WeatherInfo info = new WeatherInfo();
        String desc = codeToDesc(code);
        info.badgeText = String.format(Locale.US, "%s %.0f°C", desc, temp);

        StringBuilder det = new StringBuilder();
        String areaName = fetchLocationName(lat, lon);
        String updateTime = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        det.append("🌍 地区: ").append(areaName).append("\n");
        det.append(String.format(Locale.US, "📍 坐标: %.4f, %.4f  (更新于 %s)\n", lat, lon, updateTime));
        det.append(String.format(Locale.US, "🌡 当前: %s   %.1f°C (湿度 %d%%)\n", desc, temp, hum));
        det.append(String.format(Locale.US, "💨 风况: %.1f km/h (风向 %d°)\n\n", wind, wdir));
        det.append("🕒 今日逐时段预报:\n");

        JSONObject hr = root.getJSONObject("hourly");
        JSONArray times = hr.getJSONArray("time");
        JSONArray temps = hr.getJSONArray("temperature_2m");
        JSONArray probs = hr.getJSONArray("precipitation_probability");
        JSONArray precs = hr.getJSONArray("precipitation");
        JSONArray codes = hr.getJSONArray("weather_code");

        for (int i = 0; i < times.length(); i += 2) {
            String t = times.getString(i);
            int idxT = t.indexOf('T');
            String hhmm = idxT >= 0 ? t.substring(idxT + 1) : t;
            det.append(String.format(Locale.US, "  %s  %s  %.0f°C  降雨%d%% (%.1fmm)\n",
                hhmm, codeToDesc(codes.getInt(i)), temps.getDouble(i), probs.getInt(i), precs.getDouble(i)));
        }
        info.detailText = det.toString();
        return info;
    }
}
