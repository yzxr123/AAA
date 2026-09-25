package android.app;
import android.content.*;
public class AlertDialog {
 public void setOnCancelListener(android.content.DialogInterface.OnCancelListener l){} public void setMessage(CharSequence s){lastMessage=s.toString();} public void dismiss(){}
 public static String lastMessage="",lastTitle="";public static CharSequence[] lastItems;public static DialogInterface.OnClickListener lastListener;
 public static class Builder {
  String message="",title="";CharSequence[] items;DialogInterface.OnClickListener listener;
  public Builder(Context c){}public Builder setTitle(CharSequence s){title=String.valueOf(s);return this;}
  public Builder setMessage(CharSequence s){message=String.valueOf(s);return this;}
  public Builder setItems(CharSequence[] v,DialogInterface.OnClickListener l){items=v;listener=l;return this;}
  public Builder setPositiveButton(CharSequence s,DialogInterface.OnClickListener l){return this;}
  public Builder setNegativeButton(CharSequence s,DialogInterface.OnClickListener l){return this;}
  public Builder setNeutralButton(CharSequence s,DialogInterface.OnClickListener l){return this;}
  public AlertDialog show(){lastMessage=message;lastTitle=title;lastItems=items;lastListener=listener;return new AlertDialog();}
 }
}
