package org.adguardian.app.ocr;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import java.io.IOException;

public final class HardwareScreenshotReducer {
    private static final int TARGET_WIDTH=512;
    private static final int MAX_HEIGHT=2048;
    private HardwareScreenshotReducer() { }

    public static final class Frame implements AutoCloseable {
        public final Bitmap bitmap;
        public final int screenWidth;
        public final int screenHeight;
        Frame(Bitmap bitmap,int width,int height) {
            this.bitmap=bitmap;this.screenWidth=width;this.screenHeight=height;
        }
        @Override public void close() { bitmap.recycle(); }
    }

    public static Frame reduce(AccessibilityService.ScreenshotResult screenshot) throws IOException {
        HardwareBuffer source=screenshot.getHardwareBuffer();
        if(source==null)throw new IOException("Screenshot has no buffer");
        Bitmap hardware=null;
        Bitmap smallHardware=null;
        HardwareRenderer renderer=null;
        RenderNode node=null;
        ImageReader reader=null;
        Image image=null;
        HardwareBuffer output=null;
        try {
            hardware=Bitmap.wrapHardwareBuffer(source,screenshot.getColorSpace());
            if(hardware==null)throw new IOException("Screenshot wrapping failed");
            int width=hardware.getWidth();
            int height=hardware.getHeight();
            float scale=Math.min(1f,Math.min(TARGET_WIDTH/(float)width,MAX_HEIGHT/(float)height));
            int targetWidth=Math.max(1,Math.round(width*scale));
            int targetHeight=Math.max(1,Math.round(height*scale));
            reader=ImageReader.newInstance(targetWidth,targetHeight,PixelFormat.RGBA_8888,2,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            node=new RenderNode("OcrScreenshot");
            node.setPosition(0,0,targetWidth,targetHeight);
            RecordingCanvas canvas=node.beginRecording(targetWidth,targetHeight);
            canvas.drawBitmap(hardware,null,new Rect(0,0,targetWidth,targetHeight),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            node.endRecording();
            renderer=new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
            renderer.setOpaque(true);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            image=reader.acquireLatestImage();
            if(image==null)throw new IOException("Reduced frame unavailable");
            output=image.getHardwareBuffer();
            if(output==null)throw new IOException("Reduced buffer unavailable");
            smallHardware=Bitmap.wrapHardwareBuffer(output,screenshot.getColorSpace());
            if(smallHardware==null)throw new IOException("Reduced frame wrapping failed");
            Bitmap reduced=smallHardware.copy(Bitmap.Config.RGB_565,false);
            if(reduced==null)throw new IOException("Reduced frame readback failed");
            return new Frame(reduced,width,height);
        } finally {
            if(smallHardware!=null)smallHardware.recycle();
            if(output!=null)output.close();
            if(image!=null)image.close();
            if(renderer!=null)renderer.destroy();
            if(node!=null)node.discardDisplayList();
            if(reader!=null)reader.close();
            if(hardware!=null)hardware.recycle();
            source.close();
        }
    }
}
