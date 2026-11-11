package com.calinado.cove;

import android.app.Activity;
import android.app.Presentation;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Cove for Android. The whole interface is the same index.html as the Windows version, shown in a WebView.
 * This class is the Android stand-in for server.ps1: the page calls CoveNative.call(path, body) and gets JSON back.
 * A second screen (AYN Thor and similar) shows second.html: a big card for the selected game.
 */
public class MainActivity extends Activity {
    private static final String BASE = "https://cove.local/";
    private static final List<String> FETCH_HOSTS = Arrays.asList("www.steamgriddb.com", "api.igdb.com", "id.twitch.tv");

    private WebView web;
    private WebView second;
    private Presentation presentation;
    private File stateFile;
    private String playingId;          // game launched from Cove that has not been returned from yet
    private long playingSince;
    private final JSONArray done = new JSONArray();   // finished sessions waiting for the page to collect
    private String lastDir;            // stick / hat direction currently held
    private long lastDirAt;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        stateFile = new File(getFilesDir(), "state.json");
        web = new WebView(this);
        setContentView(web);
        setup(web);
        web.addJavascriptInterface(new Bridge(), "CoveNative");
        web.loadDataWithBaseURL(BASE, asset("index.html").replace("__TOKEN__", "android"), "text/html", "utf-8", null);
        showSecondScreen();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playingId != null) {   // back from a game: count the time away as play time
            try { done.put(new JSONObject().put("id", playingId).put("s", (SystemClock.elapsedRealtime() - playingSince) / 1000)); } catch (JSONException ignored) { }
            playingId = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (presentation != null) presentation.dismiss();
        super.onDestroy();
    }

    private void setup(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // menu sounds without a tap first
        w.setBackgroundColor(0xFF000000);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"cove.local".equals(u.getHost())) return null;   // covers and lookups go to the network as normal
                try {
                    String p = u.getPath();
                    if ("/cove.ico".equals(p)) return new WebResourceResponse("image/x-icon", null, getAssets().open("cove.ico"));
                    if ("/file".equals(p)) {   // custom artwork and wallpapers: images only
                        File f = new File(u.getQueryParameter("p"));
                        String n = f.getName().toLowerCase();
                        String t = n.endsWith(".png") ? "image/png" : n.endsWith(".webp") ? "image/webp" : n.endsWith(".gif") ? "image/gif"
                                : (n.endsWith(".jpg") || n.endsWith(".jpeg")) ? "image/jpeg" : null;
                        if (t != null && f.isFile()) return new WebResourceResponse(t, null, new FileInputStream(f));
                    }
                } catch (Exception ignored) { }
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
        });
    }

    /** If the device has a second display, put the selected-game card on it. */
    private void showSecondScreen() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (displays.length == 0) return;
            presentation = new Presentation(this, displays[0]);
            second = new WebView(presentation.getContext());
            setup(second);
            presentation.setContentView(second);
            second.loadDataWithBaseURL(BASE, asset("second.html"), "text/html", "utf-8", null);
            presentation.show();
        } catch (Exception e) {
            presentation = null;
            second = null;
        }
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) { return new String(readAll(in), StandardCharsets.UTF_8); }
        catch (Exception e) { return "<p style='color:#fff;padding:40px'>Cove could not load " + name + "</p>"; }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void js(final WebView w, final String code) {
        runOnUiThread(() -> { if (w != null) w.evaluateJavascript(code, null); });
    }

    /* ---------- controller: every button is turned into the page's own navigation call ---------- */

    private void nav(String k) { js(web, "window.navKey&&navKey('" + k + "')"); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: k = "u"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "d"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "l"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "r"; break;
            case KeyEvent.KEYCODE_BUTTON_A: k = "a"; break;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK: k = "bk"; break;
            case KeyEvent.KEYCODE_BUTTON_X: k = "x"; break;
            case KeyEvent.KEYCODE_BUTTON_Y: k = "y"; break;
            case KeyEvent.KEYCODE_BUTTON_L1: k = "lb"; break;
            case KeyEvent.KEYCODE_BUTTON_R1: k = "rb"; break;
            case KeyEvent.KEYCODE_BUTTON_START: k = "start"; break;
            case KeyEvent.KEYCODE_BUTTON_SELECT: k = "sel"; break;
        }
        boolean pad = (e.getSource() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD)) != 0 || e.getKeyCode() == KeyEvent.KEYCODE_BACK;
        if (k != null && pad) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) nav(k);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (x == 0 && y == 0) { x = e.getAxisValue(MotionEvent.AXIS_X); y = e.getAxisValue(MotionEvent.AXIS_Y); }
            String d = x < -.5f ? "l" : x > .5f ? "r" : y < -.5f ? "u" : y > .5f ? "d" : null;
            long now = SystemClock.uptimeMillis();
            if (d == null) lastDir = null;
            else if (!d.equals(lastDir) || now - lastDirAt > 190) { lastDir = d; lastDirAt = now; nav(d); }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /* ---------- the page's helper ---------- */

    private class Bridge {
        @JavascriptInterface
        public String call(String path, String body) {
            try {
                return handle(path, body == null || body.isEmpty() ? null : body);
            } catch (Throwable e) {
                try { return new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()).toString(); }
                catch (JSONException x) { return "{\"error\":\"failed\"}"; }
            }
        }
    }

    private boolean canReadStorage() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == 0;
    }

    private boolean installed(String component) {
        try { getPackageManager().getPackageInfo(component.split("/")[0], 0); return true; } catch (Exception e) { return false; }
    }

    private String handle(String path, String body) throws Exception {
        JSONObject j = body != null && body.startsWith("{") && !"state".equals(path) ? new JSONObject(body) : new JSONObject();
        JSONObject ok = new JSONObject().put("ok", true);
        switch (path) {
            case "state":
                if (body == null) return stateFile.exists() ? new String(readAll(new FileInputStream(stateFile)), StandardCharsets.UTF_8) : "{}";
                try (OutputStream o = new FileOutputStream(stateFile)) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
                return ok.toString();
            case "info":
                return new JSONObject().put("v", 6).put("android", true).put("sep", "/").put("storage", canReadStorage())
                        .put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName).put("repo", "")
                        .put("app", getFilesDir().getAbsolutePath()).put("appdata", "").put("local", "").put("docs", "").put("desktop", "")
                        .put("screens", presentation == null ? 1 : 2).toString();
            case "perm":   // send the user to the "all files access" switch for Cove
                if (Build.VERSION.SDK_INT >= 30) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                } else requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                return ok.toString();
            case "ping": {
                JSONObject r = new JSONObject().put("running", playingId == null ? 0 : 1).put("ids", playingId == null ? new JSONArray() : new JSONArray().put(playingId));
                synchronized (done) {
                    JSONArray d = new JSONArray(done.toString());
                    while (done.length() > 0) done.remove(0);
                    return r.put("done", d).toString();
                }
            }
            case "exists": {   // a path, or an emulator's package/activity
                JSONArray in = j.getJSONArray("paths"), out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    String p = in.optString(i);
                    out.put(!p.isEmpty() && (p.startsWith("/") ? new File(p).exists() : installed(p)));
                }
                return new JSONObject().put("r", out).toString();
            }
            case "apps": {
                JSONArray in = j.getJSONArray("comps"), out = new JSONArray();
                for (int i = 0; i < in.length(); i++) out.put(installed(in.getString(i)));
                return new JSONObject().put("r", out).toString();
            }
            case "mkdir": {
                JSONArray in = j.getJSONArray("paths");
                for (int i = 0; i < in.length(); i++) new File(in.getString(i)).mkdirs();
                return ok.toString();
            }
            case "ls": {
                File root = new File(j.optString("path"));
                JSONArray items = new JSONArray();
                if (root.isDirectory()) walk(root, root.getAbsolutePath().length() + 1, j.optInt("depth"), j.optJSONArray("exts"), items);
                return new JSONObject().put("root", root.getAbsolutePath()).put("items", items).toString();
            }
            case "browse": {
                String p = j.optString("path");
                JSONArray dirs = new JSONArray(), files = new JSONArray(), exts = j.optJSONArray("exts");
                if (p.isEmpty()) {   // top level: internal storage and any SD cards
                    dirs.put(Environment.getExternalStorageDirectory().getAbsolutePath());
                    File[] vols = new File("/storage").listFiles();
                    if (vols != null) for (File v : vols) if (v.isDirectory() && v.canRead() && !v.getName().equals("emulated") && !v.getName().equals("self")) dirs.put(v.getAbsolutePath());
                    return new JSONObject().put("path", "").put("parent", "").put("dirs", dirs).put("files", files).toString();
                }
                File d = new File(p);
                File[] kids = d.listFiles();
                if (kids == null) throw new Exception("Cannot open that folder");
                Arrays.sort(kids);
                for (File k : kids) {
                    if (k.isHidden()) continue;
                    if (k.isDirectory()) dirs.put(k.getName());
                    else if (hasExt(k.getName(), exts)) files.put(k.getName());
                }
                String parent = d.getParent() == null || d.equals(Environment.getExternalStorageDirectory()) || "/storage".equals(d.getParent()) ? "" : d.getParent();
                return new JSONObject().put("path", d.getAbsolutePath()).put("parent", parent).put("dirs", dirs).put("files", files).toString();
            }
            case "move": {   // never overwrites
                JSONArray m = j.getJSONArray("moves");
                int moved = 0, skipped = 0;
                for (int i = 0; i < m.length(); i++) {
                    File from = new File(m.getJSONObject(i).getString("from")), to = new File(m.getJSONObject(i).getString("to"));
                    if (to.exists() || !from.exists()) { skipped++; continue; }
                    to.getParentFile().mkdirs();
                    if (!from.renameTo(to)) { copyTree(from, to); deleteTree(from); }
                    moved++;
                }
                return new JSONObject().put("moved", moved).put("skipped", skipped).toString();
            }
            case "copy": {
                int n = 0;
                JSONArray items = j.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) n += copyTree(new File(j.getString("from"), items.getString(i)), new File(j.getString("to"), items.getString(i)));
                else n = copyTree(new File(j.getString("from")), new File(j.getString("to")));
                return new JSONObject().put("files", n).toString();
            }
            case "launch":
                launch(j);
                return ok.toString();
            case "second":
                if (second != null) js(second, "show(" + j.toString() + ")");
                return ok.toString();
            case "fetch": {   // scraper sites that refuse browsers; fixed list only
                URL u = new URL(j.getString("url"));
                if (!"https".equals(u.getProtocol()) || !FETCH_HOSTS.contains(u.getHost())) throw new Exception("That address is not allowed");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                JSONObject h = j.optJSONObject("headers");
                if (h != null) for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
                if ("POST".equals(j.optString("method"))) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    try (OutputStream o = c.getOutputStream()) { o.write(j.optString("body").getBytes(StandardCharsets.UTF_8)); }
                }
                if (c.getResponseCode() >= 400) throw new Exception("The site answered " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
            }
            case "pick":
                return new JSONObject().put("path", "").toString();
            case "quit":
                return ok.toString();
            default:
                throw new Exception("That is not available on Android yet");
        }
    }

    private static boolean hasExt(String name, JSONArray exts) {
        if (exts == null || exts.length() == 0) return false;
        int dot = name.lastIndexOf('.');
        String e = dot < 0 ? "" : name.substring(dot).toLowerCase();
        for (int i = 0; i < exts.length(); i++) if (e.equals(exts.optString(i))) return true;
        return false;
    }

    /** Lists files under dir; skips romfs (an unpacked game's assets) and hidden folders. */
    private void walk(File dir, int rootLen, int depth, JSONArray exts, JSONArray out) throws JSONException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile()) {
                if (exts == null || exts.length() == 0 || hasExt(k.getName(), exts)) out.put(new JSONObject().put("p", k.getAbsolutePath().substring(rootLen)).put("s", k.length()));
            } else if (depth > 0 && !k.isHidden() && !k.getName().equals("romfs") && !k.getName().equals("Android")) walk(k, rootLen, depth - 1, exts, out);
        }
    }

    private int copyTree(File from, File to) throws Exception {
        if (from.isFile()) {
            to.getParentFile().mkdirs();
            try (InputStream in = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            }
            return 1;
        }
        int n = 0;
        File[] kids = from.listFiles();
        if (kids != null) for (File k : kids) n += copyTree(k, new File(to, k.getName()));
        return n;
    }

    private void deleteTree(File f) {   // only used to finish a move across storage volumes, after the copy succeeded
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /* ---------- launching an emulator ---------- */

    /** content:// address for a file as Android's own file picker would give it; emulators that were granted the games folder accept it. */
    private static String safUri(String path) {
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        String vol = "primary", rel = path;
        if (path.startsWith(internal)) rel = path.substring(internal.length());
        else if (path.startsWith("/storage/")) {
            String rest = path.substring("/storage/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) { vol = rest.substring(0, slash); rel = rest.substring(slash + 1); }
        }
        return "content://com.android.externalstorage.documents/document/" + Uri.encode(vol + ":" + rel);
    }

    private String fill(String v, String path, String pkg) {
        return v.replace("{path}", path).replace("{saf}", safUri(path)).replace("{pkg}", pkg);
    }

    private void launch(JSONObject j) throws Exception {
        String[] comp = j.getString("comp").split("/");
        String pkg = comp[0], cls = comp.length > 1 ? comp[1] : "", path = j.getString("rom");
        Intent i = new Intent();
        if (cls.isEmpty()) {
            Intent main = getPackageManager().getLaunchIntentForPackage(pkg);
            if (main == null) throw new Exception("That emulator is not installed");
            i = main;
        } else i.setComponent(new ComponentName(pkg, cls.startsWith(".") ? pkg + cls : cls));
        if (!j.optString("action").isEmpty()) i.setAction(j.getString("action"));
        if (!j.optString("category").isEmpty()) i.addCategory(j.getString("category"));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (j.optBoolean("clear")) flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        i.addFlags(flags);
        switch (j.optString("data")) {   // how this emulator wants to be handed the game
            case "saf": i.setData(Uri.parse(safUri(path))); break;
            case "provider": i.setData(FileProvider.getUriForFile(this, "com.calinado.cove.files", new File(path))); break;
            case "path": i.setData(Uri.parse(path)); break;
        }
        JSONObject extras = j.optJSONObject("extras");
        if (extras != null) for (Iterator<String> it = extras.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = extras.get(k);
            if (v instanceof Boolean) i.putExtra(k, (Boolean) v); else i.putExtra(k, fill(String.valueOf(v), path, pkg));
        }
        startActivity(i);
        playingId = j.optString("id");
        playingSince = SystemClock.elapsedRealtime();
    }
}
