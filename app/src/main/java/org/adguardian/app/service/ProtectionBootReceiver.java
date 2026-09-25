package org.adguardian.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProtectionBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null)return;
        String action=intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))ProtectionService.sync(context);
    }
}
