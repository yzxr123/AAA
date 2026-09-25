package android.graphics;import android.hardware.HardwareBuffer;public class Bitmap{
public enum Config{RGB_565,ARGB_8888,HARDWARE}public int getWidth(){return 512;}public int getHeight(){return 1024;}public boolean isRecycled(){return false;}public void recycle(){}
public static Bitmap wrapHardwareBuffer(HardwareBuffer b,ColorSpace c){throw new UnsupportedOperationException("GPU unavailable in host tests");}
public static Bitmap createBitmap(Bitmap b,int x,int y,int w,int h){throw new UnsupportedOperationException();}public Bitmap copy(Config c,boolean m){throw new UnsupportedOperationException();}
}