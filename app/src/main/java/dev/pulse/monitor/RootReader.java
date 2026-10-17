package dev.pulse.monitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded telemetry shell: ADB capability, Root, or ordinary app permissions. */
final class RootReader implements AutoCloseable {
    private static final String EOF = "\u0000EOF";
    private final AtomicLong sequence = new AtomicLong();
    private Process process;
    private OutputStreamWriter input;
    private ArrayBlockingQueue<String> output;
    private volatile String status = "普通模式 · 读取公开接口";
    private boolean root;
    private long retryAfter;

    synchronized boolean ensure() {
        if (AdbBridge.available()) { status = "ADB 已连接 · 非 Root"; return true; }
        if (process != null && process.isAlive() && (root || System.currentTimeMillis() < retryAfter)) { status=root?"Root 已连接":"普通模式 · 受系统权限限制"; return true; }
        closeProcess();
        boolean tryRoot = System.currentTimeMillis() >= retryAfter;
        for (String shell : tryRoot ? new String[]{"su", "/system/bin/sh"} : new String[]{"/system/bin/sh"}) {
            try {
                process = new ProcessBuilder(shell).redirectErrorStream(true).start();
                input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(8192);
                output = queue;
                final Process child = process;
                Thread reader = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) if (!queue.offer(line, 1, TimeUnit.SECONDS)) break;
                    } catch (Exception ignored) { }
                    queue.offer(EOF);
                }, "Pulse-telemetry-reader");
                reader.setDaemon(true); reader.start();
                String id = execute("id -u", "su".equals(shell) ? 3000 : 1000);
                root = id != null && id.trim().equals("0");
                if (id != null && (root || !"su".equals(shell))) {
                    status = root ? "Root 已连接" : "普通模式 · 受系统权限限制";
                    return true;
                }
            } catch (Exception ignored) { }
            closeProcess(); retryAfter = System.currentTimeMillis() + 60000;
        }
        status = "读取失败 · 请重试";
        return false;
    }

    synchronized String read(String command, long timeoutMillis) {
        if (AdbBridge.available()) {
            status = "ADB 已连接 · 非 Root";
            return AdbBridge.read(command, timeoutMillis);
        }
        if (!ensure()) return null;
        String result = execute(command, timeoutMillis);
        if (result == null) { status = "读取超时 · 正在重连"; closeProcess(); }
        return result;
    }

    private String execute(String command, long timeoutMillis) {
        if (input == null || output == null) return null;
        String marker = "__PULSE_END_" + sequence.incrementAndGet() + "__";
        try {
            input.write("{\n" + command + "\n} 2>/dev/null\necho\necho " + marker + "\n");
            input.flush();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            StringBuilder result = new StringBuilder();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                String line = output.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null || EOF.equals(line)) return null;
                if (marker.equals(line)) return result.toString().trim();
                result.append(line).append('\n');
                if (result.length() > 1024 * 1024) return null;
            }
        } catch (Exception e) { return null; }
    }

    String status() { return status; }
    boolean isRoot() { return !AdbBridge.available() && root; }
    boolean isPrivileged() { return AdbBridge.available() || root; }

    private void closeProcess() {
        root = false;
        try { if (input != null) input.close(); } catch (Exception ignored) { }
        if (process != null) process.destroy();
        input = null; process = null; output = null;
    }

    @Override public synchronized void close() { closeProcess(); }
}
