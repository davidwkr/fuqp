package com.iodvd.fuqp.probe.target;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TargetActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String token = getIntent().getStringExtra("fuqp.token");
        if (token != null) {
            try (FileOutputStream output = openFileOutput("launch-events", MODE_APPEND)) {
                output.write((token + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot record target launch", failure);
            }
        }
        TextView label = new TextView(this);
        label.setText("FUQP target launched: " + token);
        setContentView(label);
    }
}
