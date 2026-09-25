package org.adguardian.app.log;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class UserEventLog {
    private static final String FILE_NAME = "qunideguanggao-events.log";
    private static final long MAX_BYTES = 96L * 1024L;
    private static final int MAX_READ_BYTES = 48 * 1024;
    private static final long DUPLICATE_WINDOW_MS = 5_000L;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static final Set<Watch> WATCHES = new LinkedHashSet<>();
    private static String lastNotice = "";
    private static String lastNoticeFile = "";
    private static long lastNoticeUptime;

    private UserEventLog() {
    }

    public static void record(Context context, String message) {
        write(context, message, false);
    }

    public static void recordAction(Context context, String packageName, String outcome) {
        if (context == null || clean(outcome).isEmpty()) return;
        String app = clean(packageName);
        try {
            CharSequence label = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0));
            if (label != null && !clean(label.toString()).isEmpty()) app = clean(label.toString());
        } catch (Exception unavailable) {
            // Package visibility can hide labels even while accessibility identifies the package.
        }
        app = truncate(app, 60);
        record(context, app.isEmpty() ? outcome : app + " · " + outcome);
    }

    public static void warning(Context context, String message) {
        write(context, message, true);
    }

    public static void error(Context context, String message) {
        write(context, message, true);
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (!file.isFile() || file.length() == 0L) {
                return "暂无拦截记录";
            }
            try {
                ArrayDeque<String> lines = tail(file, MAX_READ_BYTES);
                if (lines.isEmpty()) return "暂无拦截记录";
                StringBuilder result = new StringBuilder();
                Iterator<String> newest = lines.descendingIterator();
                while (newest.hasNext()) result.append(newest.next()).append('\n');
                return result.toString();
            } catch (Exception ignored) {
                return "暂时无法读取拦截记录";
            }
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (file.exists() && !file.delete()) return;
            lastNotice="";lastNoticeFile="";lastNoticeUptime=0;
            changed(file);
        }
    }

    private static void write(Context context, String message, boolean notice) {
        if (context == null) {
            return;
        }
        String clean = clean(message);
        if (clean.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            File file = file(context);
            if (notice && clean.equals(lastNotice) && file.getAbsolutePath().equals(lastNoticeFile)
                    && now - lastNoticeUptime < DUPLICATE_WINDOW_MS) {
                return;
            }
            try {
                String line = CLOCK.format(new Date()) + "  " + clean + "\n";
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                if (file.length() + bytes.length > MAX_BYTES) {
                    rotate(file, bytes);
                } else {
                    try (FileOutputStream output = new FileOutputStream(file, true)) {
                        output.write(bytes);
                        output.getFD().sync();
                    }
                }
                if (notice) {
                    lastNotice=clean;lastNoticeFile=file.getAbsolutePath();lastNoticeUptime=now;
                }
                changed(file);
            } catch (Exception ignored) {
            }
        }
    }

    private static ArrayDeque<String> tail(File file, int maxBytes) throws java.io.IOException {
        ArrayDeque<String> lines = new ArrayDeque<>();
        int bytes = 0;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new AtomicFile(file).openRead(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) continue;
                lines.addLast(line);
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                while (bytes > maxBytes && !lines.isEmpty()) {
                    bytes -= lines.removeFirst().getBytes(StandardCharsets.UTF_8).length + 1;
                }
            }
        }
        return lines;
    }

    private static void rotate(File file, byte[] nextLine) throws java.io.IOException {
        ArrayDeque<String> retained = tail(file, MAX_READ_BYTES);
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            long expectedBytes = nextLine.length;
            for (String line : retained) {
                byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                output.write(bytes);
                expectedBytes += bytes.length;
            }
            output.write(nextLine);
            output.getFD().sync();
            atomic.finishWrite(output);
            // AtomicFile logs a failed rename rather than throwing; rotation must shrink the base.
            if (file.length() != expectedBytes) throw new java.io.IOException("Event rotation did not commit");
        } catch (Exception failure) {
            if (output != null) atomic.failWrite(output);
            throw new java.io.IOException("Cannot retain event history", failure);
        }
    }

    private static void changed(File file) {
        for (Watch watch : WATCHES) if (watch.file.equals(file)) watch.changed();
    }

    public static final class Watch implements AutoCloseable {
        private final File file;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable notify;
        private volatile boolean closed;

        public Watch(Context context, Runnable callback) {
            file = UserEventLog.file(context);
            notify = () -> { if (!closed) callback.run(); };
            synchronized (LOCK) { WATCHES.add(this); }
        }

        private void changed() {
            if (closed) return;
            handler.removeCallbacks(notify);
            handler.post(notify);
        }

        @Override public void close() {
            synchronized (LOCK) {
                closed = true;
                WATCHES.remove(this);
                handler.removeCallbacksAndMessages(null);
            }
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return truncate(clean, 120);
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
}
