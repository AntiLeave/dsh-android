package ai.deepseek.dshbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Setup and status screen for the loopback accessibility bridge. */
public final class MainActivity extends Activity {
  private TextView status;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(20), dp(20), dp(20));
    root.setBackgroundColor(Color.WHITE);

    TextView title = new TextView(this);
    title.setText("DSH Accessibility Bridge");
    title.setTextSize(24);
    title.setTextColor(Color.rgb(21, 101, 192));
    title.setGravity(Gravity.START);
    root.addView(title, matchWrap());

    TextView help = new TextView(this);
    help.setText("1. Enable \"DSH Accessibility Bridge\" in Settings > Accessibility.\n"
        + "2. The service listens only on 127.0.0.1:17654.\n"
        + "3. Start the harness (root shell or Termux):\n"
        + "   node --expose-internals $PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js "
        + "--profile web --patch $HOME/.dsh/android.cordis.yml "
        + "--host 127.0.0.1 --port 3080 --no-open\n"
        + "4. Open http://127.0.0.1:3080 on this device.\n\n"
        + "The agent tools are android_state, android_act, android_apps, "
        + "android_screenshot, android_shell, and android_health.");
    help.setTextSize(15);
    help.setTextColor(Color.DKGRAY);
    help.setPadding(0, dp(14), 0, dp(14));
    root.addView(help, matchWrap());

    Button accessibility = new Button(this);
    accessibility.setText("Open accessibility settings");
    accessibility.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
      }
    });
    root.addView(accessibility, matchWrap());

    Button refresh = new Button(this);
    refresh.setText("Test bridge connection");
    refresh.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        refreshStatus();
      }
    });
    root.addView(refresh, matchWrap());

    status = new TextView(this);
    status.setTextSize(14);
    status.setTextColor(Color.rgb(27, 94, 32));
    status.setPadding(0, dp(14), 0, dp(14));
    status.setMovementMethod(new ScrollingMovementMethod());
    root.addView(status, matchWrap());

    scroll.addView(root);
    setContentView(scroll);
    refreshStatus();
  }

  private void refreshStatus() {
    status.setText("Checking 127.0.0.1:17654 ...");
    new Thread(new Runnable() {
      @Override
      public void run() {
        final String message = probe();
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            status.setText(message);
          }
        });
      }
    }, "dsh-bridge-status").start();
  }

  private String probe() {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL("http://127.0.0.1:17654/health").openConnection();
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(1500);
      int code = connection.getResponseCode();
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
      StringBuilder body = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) body.append(line);
      return "Bridge active (HTTP " + code + ")\n" + body;
    } catch (Exception error) {
      return "Bridge not reachable: " + error.getMessage()
          + "\n\nEnable the accessibility service, then tap Test again.";
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private LinearLayout.LayoutParams matchWrap() {
    return new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
