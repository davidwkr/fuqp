package com.iodvd.fuqp.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

public class ProbeActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label = new TextView(this);
        label.setText("FUQP caller: " + getPackageName());
        setContentView(label);
    }
}
