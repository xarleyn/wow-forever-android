package com.winlator.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded capture for optional probes. The caller must merge stderr into stdout. */
public final class ProcessOutputCapture {
    private ProcessOutputCapture() {}

    public static String capture(Process process, int timeoutSeconds, int maxChars) throws InterruptedException {
        if (timeoutSeconds < 1 || maxChars < 1) throw new IllegalArgumentException("Positive capture limits required");
        Tail tail = new Tail(maxChars);
        Thread reader = new Thread(() -> {
            try (InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int count;
                while ((count = input.read(buffer)) != -1) tail.append(buffer, count);
            } catch (IOException ignored) {}
        }, "probe-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            // A Wine child can retain the pipe after the probe exits. Never wait for its EOF
            // or close its stream on the launch thread: both may block on a pending read.
            reader.join(250);
            String status = finished ? "PROBE_PROCESS exit=" + process.exitValue()
                    : "PROBE_PROCESS timeout=" + timeoutSeconds + "s (termination requested)";
            return status + "\n" + tail.snapshot();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        } finally {
            Thread closer = new Thread(() -> {
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }, "probe-close-pipes");
            closer.setDaemon(true);
            closer.start();
        }
    }

    private static final class Tail {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;
        Tail(int limit) { this.limit = limit; }
        synchronized void append(char[] chars, int count) {
            int skip = Math.max(0, count - limit);
            text.append(chars, skip, count - skip);
            int overflow = text.length() - limit;
            if (overflow > 0) text.delete(0, overflow);
            truncated |= skip > 0 || overflow > 0;
        }
        synchronized String snapshot() {
            return (truncated ? "[earlier probe output truncated]\n" : "") + text;
        }
    }
}
