package com.example.motonav;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Runs actual release Kotlin code against controlled HTTPS failures, without contacting a phone. */
public final class UpdateTransportTest {
    static final String BASE = "https://github.com/shuiyeshanchuan/MotoNav/releases/download/motonav-v8.3.69/";
    static final String API = "https://api.github.com/repos/shuiyeshanchuan/MotoNav/releases/assets/123";
    static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    static final AtomicInteger calls = new AtomicInteger(), disconnects = new AtomicInteger();
    static int assertions;
    static class Reply {
        int code = 200; byte[] data; boolean stall, broken; long length = -1;
        Reply(String value) { data = value.getBytes(StandardCharsets.UTF_8); }
        Reply status(int value) { code = value; return this; }
        Reply stall() { stall = true; return this; }
        Reply broken() { broken = true; return this; }
        Reply oversized() { length = 3_000_000; return this; }
    }
    static class Connection extends HttpURLConnection {
        final Reply reply; final CountDownLatch closed = new CountDownLatch(1);
        Connection(URL url) { super(url); calls.incrementAndGet(); reply = replies.getOrDefault(key(url.toString()), new Reply("").status(504)); }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnects.incrementAndGet(); closed.countDown(); }
        public int getResponseCode() {
            if (reply.stall) {
                boolean done = false;
                while (!done) try { done = closed.await(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                throw new RuntimeException("socket disconnected");
            }
            return reply.code;
        }
        public long getContentLengthLong() { return reply.length; }
        public InputStream getInputStream() {
            if (reply.broken) return new InputStream() {
                int position;
                public int read() throws IOException { if (position++ == 0) return 'X'; throw new IOException("test interrupted transfer"); }
            };
            return new ByteArrayInputStream(reply.data);
        }
    }
    static String key(String url) { return url.contains("raw.githubusercontent") ? "raw" : url.contains("/contents/") ? "contents" : url.contains("/releases?") ? "releases" : url; }
    static void reset() throws Exception {
        replies.clear(); calls.set(0); disconnects.set(0);
        Field cache = GitHubUpdateSource.class.getDeclaredField("cachedFeed"); cache.setAccessible(true); cache.set(null, null);
    }
    static void expect(boolean good, String message) { assertions++; if (!good) throw new AssertionError(message); }
    interface Checked { void run() throws Exception; }
    static void fails(Checked action, String text) throws Exception {
        try { action.run(); throw new AssertionError("expected failure " + text); }
        catch (Exception e) { expect(e.getMessage() != null && e.getMessage().contains(text), "error should contain " + text + ": " + e); }
    }
    static JSONObject app() { return new JSONObject().put("version", "v8.3.69").put("apkUrl", BASE + "app-release.apk")
        .put("apkApiUrl", API).put("bytes", 3).put("releaseTag", "motonav-v8.3.69"); }
    static String feed() { return new JSONObject().put("schema", 1).put("app", app()).put("firmwares", new JSONArray()).toString(); }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL url) { return new Connection(url); }
        } : null);
        reset(); replies.put("contents", new Reply(feed()));
        expect(AppUpdateClient.INSTANCE.check().getVersion().equals("v8.3.69"), "raw504 must fall back to official contents");
        expect(calls.get() == 2, "two metadata sources, no release scan");
        expect(AppUpdateClient.INSTANCE.check().getVersion().equals("v8.3.69") && calls.get() == 2, "15sec cache coalesces app/firmware checks");
        reset(); replies.put("raw", new Reply(feed()));
        ExecutorService concurrent = Executors.newFixedThreadPool(8);
        List<Future<?>> checks = new ArrayList<>();
        for (int i=0;i<8;i++) checks.add(concurrent.submit(() -> AppUpdateClient.INSTANCE.check()));
        for (Future<?> check : checks) check.get(2, TimeUnit.SECONDS);
        concurrent.shutdown(); expect(calls.get() == 1, "concurrent checks should share one fresh feed");
        JSONObject release = new JSONObject().put("tag_name", "motonav-v8.3.69").put("assets", new JSONArray().put(
            new JSONObject().put("name", "app-release.apk").put("size", 3).put("id",123).put("browser_download_url",BASE+"app-release.apk")));
        reset(); replies.put("raw", new Reply("not json")); replies.put("releases", new Reply(new JSONArray().put(release).toString()));
        expect(AppUpdateClient.INSTANCE.check().getApkApiUrl().equals(API), "malformed feed falls back to bounded release API");
        expect(calls.get() == 3, "fallback is 3 metadata calls");
        reset(); replies.put("raw", new Reply(feed()).oversized()); replies.put("contents", new Reply(feed()).oversized());
        fails(() -> AppUpdateClient.INSTANCE.check(), "超过允许大小");
        fails(() -> AppUpdateClient.INSTANCE.parseFeedCandidate(app().put("apkUrl", "https://evil.invalid/app-release.apk")), "资产无效");
        fails(() -> AppUpdateClient.INSTANCE.parseFeedCandidate(app().put("releaseTag", "motonav-v8.3.68")), "资产无效");
        fails(() -> AppUpdateClient.INSTANCE.parseFeedCandidate(app().put("apkApiUrl", API + "?token=no")), "资产无效");
        reset(); replies.put(API, new Reply("ABC"));
        byte[] data = GitHubUpdateSource.INSTANCE.downloadAsset(BASE + "firmware.bin", API, 16, null);
        expect(Arrays.equals(data, "ABC".getBytes(StandardCharsets.UTF_8)) && calls.get() == 1, "asset API is primary download route");
        reset(); replies.put(API, new Reply("X").broken()); replies.put(BASE + "app-release.apk", new Reply("ABC"));
        File file = File.createTempFile("motonav-update-test", ".part");
        try {
            GitHubUpdateSource.INSTANCE.downloadFile(BASE + "app-release.apk", API, 16, 3, file, (a,b) -> kotlin.Unit.INSTANCE);
            expect(Files.readString(file.toPath()).equals("ABC"), "fallback must truncate partial APK before retry");
        } finally { file.delete(); }
        fails(() -> GitHubUpdateSource.INSTANCE.downloadAsset(BASE + "firmware.bin", "https://evil.invalid/123", 16, null), "官方发布资产");
        reset(); replies.put("raw", new Reply("").stall()); replies.put("contents", new Reply("").stall()); replies.put("releases", new Reply("").stall());
        long start = System.nanoTime();
        fails(() -> AppUpdateClient.INSTANCE.check(), "超时");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        expect(elapsed >= 11_000 && elapsed < 15_700, "3 stalled origins must finish within check budget: " + elapsed);
        expect(calls.get() == 3 && disconnects.get() >= 2, "timed-out sockets are disconnected, not just future-cancelled");
        reset(); replies.put("releases", new Reply("").stall());
        GitHubUpdateSource.Session deadlineSession = GitHubUpdateSource.INSTANCE.session();
        start = System.nanoTime();
        for (int i=0;i<4;i++) fails(() -> deadlineSession.releases(), "超时");
        long totalElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        expect(totalElapsed >= 14_000 && totalElapsed < 15_700, "later attempts must share the original 15sec deadline: " + totalElapsed);
        fails(() -> deadlineSession.releases(), "超时");
        expect(calls.get() == 4, "expired session cannot schedule another request");
        reset();
        GitHubUpdateSource.Session session = GitHubUpdateSource.INSTANCE.session();
        for (int i=0;i<6;i++) fails(() -> session.releases(), "HTTP 504");
        fails(() -> session.releases(), "多次失败");
        expect(calls.get() == 6, "request budget stops extra network work");
        System.out.println("PASS: update transport " + assertions + " assertions; stall test " + elapsed + "ms; total deadline " + totalElapsed + "ms; no live SMS/BLE/network used");
    }
}
