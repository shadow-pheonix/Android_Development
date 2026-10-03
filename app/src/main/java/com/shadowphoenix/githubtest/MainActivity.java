package com.shadowphoenix.githubtest;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private int clicks = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("GitHub Test App");
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);

        TextView status = new TextView(this);
        status.setText("APK built successfully with GitHub Actions.");
        status.setTextSize(17f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(18), 0, dp(28));
        status.setLayoutParams(statusParams);

        Button button = new Button(this);
        button.setText("Test button");

        TextView counter = new TextView(this);
        counter.setText("Button not pressed yet");
        counter.setTextSize(16f);
        counter.setGravity(Gravity.CENTER);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clicks++;
                counter.setText("Button pressed " + clicks + (clicks == 1 ? " time" : " times"));
            }
        });

        root.addView(title);
        root.addView(status);
        root.addView(button);
        root.addView(counter);

        setContentView(root);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
