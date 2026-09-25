package org.adguardian.app.ocr;

import android.content.Context;
import android.accessibilityservice.AccessibilityService;
import java.util.concurrent.CancellationException;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel;
import com.googlecode.tesseract.android.ResultIterator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OcrAdDetector {
    public interface Callback {
        void onResult(Result result);
        void onError(Exception error);
    }

    public static final class Result {
        public final boolean targetFound;
        public final Rect targetBounds;
        public final String targetText;
        public final String evidence;
        public final boolean adEvidence;
        public final boolean jumpEvidence;
        public final boolean shakeEvidence;
        public final boolean commercialEvidence;
        public final boolean onboardingEvidence;

        private Result(
                boolean targetFound,
                Rect targetBounds,
                String targetText,
                String evidence,
                boolean adEvidence,
                boolean jumpEvidence,
                boolean shakeEvidence,
                boolean commercialEvidence,
                boolean onboardingEvidence
        ) {
            this.targetFound = targetFound;
            this.targetBounds = targetBounds == null ? null : new Rect(targetBounds);
            this.targetText = targetText == null ? "" : targetText;
            this.evidence = evidence == null ? "" : evidence;
            this.adEvidence = adEvidence;
            this.jumpEvidence = jumpEvidence;
            this.shakeEvidence = shakeEvidence;
            this.commercialEvidence = commercialEvidence;
            this.onboardingEvidence = onboardingEvidence;
        }

        public boolean strongEvidence() {
            return jumpEvidence || shakeEvidence || commercialEvidence || adEvidence;
        }

        public boolean forceCloseEvidence() {
            return jumpEvidence || shakeEvidence || commercialEvidence;
        }
    }

    private static final int OCR_TILE_HEIGHT = 512;
    private static final int OCR_TILE_OVERLAP = 64;
    private static final int MIN_DIRECT_CONFIDENCE = 34;
    private static final int MIN_EVIDENCE_CONFIDENCE = 24;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private TessBaseAPI api;
    private volatile boolean disabled;
    private boolean closed;
    private volatile long generation;

    public OcrAdDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isBusy() {
        return busy.get();
    }

    public boolean analyzeScreenshot(AccessibilityService.ScreenshotResult screenshot, Callback callback) {
        if (disabled || callback == null || !busy.compareAndSet(false, true)) return false;
        long token=generation;
        worker.execute(() -> {
            try (HardwareScreenshotReducer.Frame frame=HardwareScreenshotReducer.reduce(screenshot)) {
                if(disabled || token!=generation)throw new CancellationException();
                Result result=analyzeBlocking(frame.bitmap,frame.screenWidth,frame.screenHeight,token);
                mainHandler.post(() -> { busy.set(false);callback.onResult(result); });
            } catch (Exception exception) {
                mainHandler.post(() -> { busy.set(false);callback.onError(exception); });
            }
        });
        return true;
    }

    public void cancel() { generation++; }

    public void close() {
        if(closed)return;
        closed=true;
        disabled=true;
        generation++;
        worker.execute(() -> {
            if(api!=null) { api.recycle();api=null; }
        });
        worker.shutdown();
    }

    private Result analyzeBlocking(Bitmap source, int screenWidth, int screenHeight,long token) throws IOException {
        TessBaseAPI tess = api();
        if (tess == null) {
            throw new IllegalStateException("Tesseract OCR unavailable");
        }
        int realWidth = screenWidth > 0 ? screenWidth : source.getWidth();
        int realHeight = screenHeight > 0 ? screenHeight : source.getHeight();
        float mapX = realWidth / (float) source.getWidth();
        float mapY = realHeight / (float) source.getHeight();
        List<LineInfo> items = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        int sourceHeight = source.getHeight();
        int step = Math.max(1, OCR_TILE_HEIGHT - OCR_TILE_OVERLAP);
        for (int top = 0; top < sourceHeight; top += step) {
            if(disabled || token!=generation)throw new CancellationException();
            int height = Math.min(OCR_TILE_HEIGHT, sourceHeight - top);
            Bitmap tile = Bitmap.createBitmap(source, 0, top, source.getWidth(), height);
            try {
                tess.setImage(tile);
                String text = normalize(tess.getUTF8Text());
                if (!text.isEmpty()) {
                    if (fullText.length() > 0) fullText.append(' ');
                    fullText.append(text);
                }
                collect(tess, PageIteratorLevel.RIL_TEXTLINE, items, mapX, mapY, top);
                collect(tess, PageIteratorLevel.RIL_WORD, items, mapX, mapY, top);
            } finally {
                tess.clear();
                if (tile != source && !tile.isRecycled()) tile.recycle();
            }
            if (top + height >= sourceHeight) break;
        }
        return evaluate(items, fullText.toString(), realWidth, realHeight);
    }

    private TessBaseAPI api() throws IOException {
        if (disabled) {
            return null;
        }
        if (api != null) {
            return api;
        }
        File baseDir = new File(context.getFilesDir(), "tesseract");
        File tessdataDir = new File(baseDir, "tessdata");
        if (!tessdataDir.exists() && !tessdataDir.mkdirs()) {
            throw new IOException("cannot create tesseract data directory");
        }
        File trainedData = new File(tessdataDir, "chi_sim.traineddata");
        if (!trainedData.isFile() || trainedData.length() < 2_000_000L) {
            copyAsset("tessdata/chi_sim.traineddata", trainedData);
        }

        TessBaseAPI created = new TessBaseAPI();
        boolean initialized = created.init(
                baseDir.getAbsolutePath(),
                "chi_sim",
                TessBaseAPI.OEM_LSTM_ONLY
        );
        if (!initialized) {
            created.recycle();
            disabled = true;
            throw new IOException("cannot initialize Tesseract chi_sim");
        }
        created.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
        api = created;
        return api;
    }

    private void copyAsset(String assetName, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("cannot replace tesseract model");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("cannot install tesseract model");
        }
    }

    private void collect(
            TessBaseAPI tess,
            int level,
            List<LineInfo> out,
            float mapX,
            float mapY,
            int sourceTop
    ) {
        ResultIterator iterator = tess.getResultIterator();
        if (iterator == null) {
            return;
        }
        try {
            iterator.begin();
            do {
                String value = normalize(iterator.getUTF8Text(level));
                Rect rect = iterator.getBoundingRect(level);
                float confidence = iterator.confidence(level);
                if (!value.isEmpty() && rect != null && rect.width() > 0 && rect.height() > 0) {
                    out.add(new LineInfo(value, mapRect(rect, mapX, mapY, sourceTop), confidence));
                }
            } while (iterator.next(level));
        } finally {
            iterator.delete();
        }
    }

    private Rect mapRect(Rect source, float mapX, float mapY, int sourceTop) {
        return new Rect(
                Math.max(0, Math.round(source.left * mapX)),
                Math.max(0, Math.round((source.top + sourceTop) * mapY)),
                Math.max(1, Math.round(source.right * mapX)),
                Math.max(1, Math.round((source.bottom + sourceTop) * mapY))
        );
    }

    private Result evaluate(List<LineInfo> items, String fullText, int width, int height) {
        boolean adEvidence = containsAdEvidence(fullText);
        boolean jumpEvidence = containsJumpEvidence(fullText);
        boolean shakeEvidence = containsShakeEvidence(fullText);
        boolean commercialEvidence = containsCommercialEvidence(fullText);
        boolean onboardingEvidence = containsOnboarding(fullText);

        for (LineInfo item : items) {
            if (item.confidence < MIN_EVIDENCE_CONFIDENCE) {
                continue;
            }
            adEvidence |= containsAdEvidence(item.text);
            jumpEvidence |= containsJumpEvidence(item.text);
            shakeEvidence |= containsShakeEvidence(item.text);
            commercialEvidence |= containsCommercialEvidence(item.text);
            onboardingEvidence |= containsOnboarding(item.text);
        }

        boolean strongEvidence = adEvidence || jumpEvidence || shakeEvidence || commercialEvidence;
        Candidate best = null;
        for (LineInfo item : items) {
            if (item.confidence < MIN_DIRECT_CONFIDENCE) {
                continue;
            }
            String action = classifyDirectAction(item.text, strongEvidence);
            if (action == null || containsNonAdFlow(item.text)) {
                continue;
            }
            if (!safeTargetBounds(item.bounds, width, height, action)) {
                continue;
            }
            int score = score(item, width, height, action, strongEvidence);
            if (best == null || score > best.score) {
                best = new Candidate(action, item.text, item.bounds, score);
            }
        }

        if (onboardingEvidence && best != null && !"ocr-explicit-ad-action".equals(best.evidence)) {
            best = null;
        }

        String summary = evidenceSummary(adEvidence, jumpEvidence, shakeEvidence, commercialEvidence);
        return new Result(
                best != null,
                best == null ? null : best.bounds,
                best == null ? "" : best.text,
                best == null ? summary : best.evidence,
                adEvidence,
                jumpEvidence,
                shakeEvidence,
                commercialEvidence,
                onboardingEvidence
        );
    }

    private String classifyDirectAction(String value, boolean strongEvidence) {
        String compact = value.replace(" ", "").replace("\n", "");
        String lower = compact.toLowerCase(Locale.ROOT);
        if (compact.length() > 28) {
            return null;
        }
        if (compact.contains("关闭广告")
                || compact.contains("跳过广告")
                || compact.contains("关闭推广")
                || compact.contains("关闭此广告")
                || compact.contains("跳过此广告")
                || compact.contains("关闭该广告")) {
            return "ocr-explicit-ad-action";
        }
        if (compact.contains("跳过") || compact.contains("跳過")) {
            return "ocr-skip";
        }
        if (lower.equals("skip") || lower.equals("skipad") || lower.equals("skipads")
                || lower.equals("closead") || lower.equals("dismissad")) {
            return "ocr-skip-en";
        }
        if (strongEvidence && (compact.equals("关闭") || compact.equals("×") || compact.equals("✕")
                || compact.equals("╳") || lower.equals("close") || lower.equals("x"))) {
            return "ocr-context-close";
        }
        return null;
    }

    private boolean containsAdEvidence(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("广告")
                || value.contains("推广")
                || value.contains("赞助")
                || value.contains("互动广告")
                || lower.contains("advertisement")
                || lower.contains("sponsored");
    }

    private boolean containsCommercialEvidence(String value) {
        return value.contains("立即下载")
                || value.contains("立即安装")
                || value.contains("立即打开")
                || value.contains("了解详情")
                || value.contains("查看详情")
                || value.contains("立即领取")
                || value.contains("点击领取")
                || value.contains("广告主")
                || value.contains("下载应用")
                || value.contains("去看看");
    }

    private boolean containsJumpEvidence(String value) {
        return OcrTextEvidence.jump(value);
    }

    private boolean containsShakeEvidence(String value) {
        return OcrTextEvidence.shake(value);
    }

    private boolean containsOnboarding(String value) {
        return value.contains("下一步")
                || value.contains("选择偏好")
                || value.contains("选择兴趣")
                || value.contains("阅读并同意")
                || value.contains("权限设置")
                || value.contains("开始使用")
                || value.contains("完善资料")
                || value.contains("隐私政策")
                || value.contains("用户协议");
    }

    private boolean containsNonAdFlow(String value) {
        return value.contains("教程")
                || value.contains("引导")
                || value.contains("片头")
                || value.contains("片尾")
                || value.contains("视频设置")
                || value.contains("帮助");
    }

    private boolean safeTargetBounds(Rect bounds, int width, int height, String evidence) {
        if (width <= 0 || height <= 0 || bounds == null) {
            return false;
        }
        long area = (long) bounds.width() * bounds.height();
        long screenArea = (long) width * height;
        if (area <= 0 || area * 3L >= screenArea) {
            return false;
        }
        if (bounds.width() >= width * 0.68f || bounds.height() >= height * 0.25f) {
            return false;
        }
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        if ("ocr-explicit-ad-action".equals(evidence)) {
            return cy <= height * 0.90f;
        }
        if ("ocr-context-close".equals(evidence)) {
            return cy <= height * 0.84f;
        }
        boolean side = cx <= width * 0.45f || cx >= width * 0.55f;
        return side && cy <= height * 0.72f;
    }

    private int score(LineInfo item, int width, int height, String evidence, boolean strongEvidence) {
        int score;
        if ("ocr-explicit-ad-action".equals(evidence)) {
            score = 120;
        } else if ("ocr-skip".equals(evidence) || "ocr-skip-en".equals(evidence)) {
            score = 90;
        } else {
            score = 55;
        }
        score += Math.min(25, Math.round(item.confidence / 4.0f));
        if (strongEvidence) {
            score += 25;
        }
        if (item.bounds.exactCenterX() >= width * 0.62f) {
            score += 18;
        }
        if (item.bounds.exactCenterY() <= height * 0.30f) {
            score += 12;
        }
        return score;
    }

    private String evidenceSummary(
            boolean adEvidence,
            boolean jumpEvidence,
            boolean shakeEvidence,
            boolean commercialEvidence
    ) {
        StringBuilder builder = new StringBuilder();
        if (adEvidence) builder.append("ad");
        if (jumpEvidence) append(builder, "third-party-jump");
        if (shakeEvidence) append(builder, "shake");
        if (commercialEvidence) append(builder, "commercial-cta");
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private void append(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append('+');
        builder.append(value);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static final class LineInfo {
        final String text;
        final Rect bounds;
        final float confidence;

        private LineInfo(String text, Rect bounds, float confidence) {
            this.text = text;
            this.bounds = new Rect(bounds);
            this.confidence = confidence;
        }
    }

    private static final class Candidate {
        final String evidence;
        final String text;
        final Rect bounds;
        final int score;

        private Candidate(String evidence, String text, Rect bounds, int score) {
            this.evidence = evidence;
            this.text = text;
            this.bounds = new Rect(bounds);
            this.score = score;
        }
    }
}
