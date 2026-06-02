package io.github.ryo100794.termport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class TermuxRunCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "TermPort";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.w(TAG, "RUN_COMMAND result intent is null");
            return;
        }
        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            Log.w(TAG, "RUN_COMMAND result bundle is null: " + intent.getExtras());
            return;
        }
        result.setClassLoader(getClass().getClassLoader());
        Log.i(TAG, "RUN_COMMAND result exitCode=" + result.getInt("exitCode")
                + " err=" + result.getInt("err")
                + " errmsg=" + result.getString("errmsg")
                + " stdout=" + result.getString("stdout")
                + " stderr=" + result.getString("stderr"));
    }
}
