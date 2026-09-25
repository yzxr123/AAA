package android.graphics;public class Rect {
public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){set(l,t,r,b);}public Rect(Rect r){set(r);}
public void set(Rect r){set(r.left,r.top,r.right,r.bottom);}public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
public int width(){return right-left;}public int height(){return bottom-top;}public float exactCenterX(){return (left+right)*.5f;}public float exactCenterY(){return (top+bottom)*.5f;}public int centerX(){return (left+right)/2;}public int centerY(){return (top+bottom)/2;}
public boolean contains(int x,int y){return x>=left&&x<right&&y>=top&&y<bottom;}public boolean contains(Rect r){return left<=r.left&&top<=r.top&&right>=r.right&&bottom>=r.bottom;}
public static boolean intersects(Rect a,Rect b){return a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom;}public boolean isEmpty(){return width()<=0||height()<=0;}
}