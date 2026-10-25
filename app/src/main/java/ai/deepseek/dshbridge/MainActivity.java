package ai.deepseek.dshbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Setup and status screen for the loopback accessibility bridge. */
public final class MainActivity extends Activity {
  private static final String PREFS = "dsh";
  private static final String PREF_URL = "root_url";

  private TextView status;
  private Button startRoot;
  private Button stopDsh;
  private Button openDsh;
  private Button copyUrl;
  private SharedPreferences preferences;
  private String dshUrl = "";
  private String lastLog = "";
  private boolean dshBusy;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
    dshUrl = preferences.getString(PREF_URL, "");

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
        + "2. Start the harness with the buttons below (root is required for now).\n"
        + "3. Use Open Web UI to open the harness; it never opens automatically.\n"
        + "4. The bridge listens only on 127.0.0.1:17654.");
    help.setTextSize(15);
    help.setTextColor(Color.DKGRAY);
    help.setPadding(0, dp(14), 0, dp(14));
    root.addView(help, matchWrap());

    startRoot = new Button(this);
    startRoot.setText("Start DSH (root)");
    startRoot.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        startRootDsh();
      }
    });
    root.addView(startRoot, matchWrap());

    stopDsh = new Button(this);
    stopDsh.setText("Stop DSH");
    stopDsh.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        stopDsh();
      }
    });
    root.addView(stopDsh, matchWrap());

    copyUrl = new Button(this);
    copyUrl.setText("Copy Web UI URL");
    copyUrl.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        copyWebUiUrl();
      }
    });
    root.addView(copyUrl, matchWrap());

    openDsh = new Button(this);
    openDsh.setText("Open Web UI");
    openDsh.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        openWebUi();
      }
    });
    root.addView(openDsh, matchWrap());

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

    Button copyLog = new Button(this);
    copyLog.setText("Copy log");
    copyLog.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        copyLog();
      }
    });
    root.addView(copyLog, matchWrap());

    status = new TextView(this);
    status.setTextSize(13);
    status.setTextColor(Color.rgb(220, 230, 220));
    status.setBackgroundColor(Color.rgb(24, 26, 27));
    status.setTypeface(Typeface.MONOSPACE);
    status.setTextIsSelectable(true);
    status.setLongClickable(true);
    status.setGravity(Gravity.TOP | Gravity.START);
    status.setPadding(dp(12), dp(12), dp(12), dp(12));
    status.setMinLines(10);
    root.addView(status, matchWrap(24));

    scroll.addView(root);
    setContentView(scroll);
    applySystemBars(scroll);
    updateDshButtons();
    refreshStatus();
  }

  private void startRootDsh() {
    dshBusy = true;
    lastLog = "";
    updateDshButtons();
    status.setText("Starting dsh as root...");
    DshLauncher.startRoot(new DshLauncher.Callback() {
      @Override
      public void onProgress(final String log) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            lastLog = log == null ? "" : log;
            if (!lastLog.isEmpty()) status.setText(lastLog);
          }
        });
      }

      @Override
      public void onResult(final String url, final String error) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            dshBusy = false;
            if (error != null) {
              status.setText(lastLog.isEmpty() ? error : lastLog);
            } else if (url == null || url.isEmpty()) {
              status.setText(lastLog.isEmpty() ? "dsh did not return a Web UI URL." : lastLog);
            } else {
              dshUrl = url;
              preferences.edit().putString(PREF_URL, dshUrl).apply();
              status.setText(lastLog.isEmpty() ? url : lastLog);
            }
            updateDshButtons();
          }
        });
      }
    });
  }

  private void stopDsh() {
    dshBusy = true;
    updateDshButtons();
    status.setText("Stopping dsh...");
    DshLauncher.stopRoot(new DshLauncher.Callback() {
      @Override
      public void onProgress(String log) {
      }

      @Override
      public void onResult(final String url, final String error) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            dshBusy = false;
            if (error != null) {
              status.setText(error);
            } else {
              dshUrl = "";
              lastLog = "";
              preferences.edit().remove(PREF_URL).apply();
              status.setText("dsh stopped.");
            }
            updateDshButtons();
          }
        });
      }
    });
  }

  private void openWebUi() {
    if (dshUrl.isEmpty()) {
      status.setText("No DSH Web UI URL yet. Start DSH first.");
      return;
    }
    try {
      Intent intent = new Intent(this, DshWebActivity.class);
      intent.putExtra(DshWebActivity.EXTRA_URL, dshUrl);
      startActivity(intent);
    } catch (Exception error) {
      status.setText("Unable to open Web UI: " + error.getMessage());
    }
  }

  private void copyWebUiUrl() {
    if (dshUrl.isEmpty()) {
      status.setText("No DSH Web UI URL yet. Start DSH first.");
      return;
    }
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH Web UI URL", dshUrl));
    Toast.makeText(this, "Web UI URL copied", Toast.LENGTH_SHORT).show();
  }

  private void copyLog() {
    String text = status.getText().toString();
    if (text.isEmpty()) return;
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH log", text));
    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
  }

  private void updateDshButtons() {
    startRoot.setEnabled(!dshBusy);
    stopDsh.setEnabled(!dshBusy);
    copyUrl.setEnabled(!dshBusy && !dshUrl.isEmpty());
    openDsh.setEnabled(!dshBusy && !dshUrl.isEmpty());
  }

  private static void applySystemBars(final View view) {
    view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override
      public WindowInsets onApplyWindowInsets(View target, WindowInsets insets) {
        Insets bars = insets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        target.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        return insets;
      }
    });
    view.requestApplyInsets();
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

  private LinearLayout.LayoutParams matchWrap(int topMargin) {
    LinearLayout.LayoutParams params = matchWrap();
    params.topMargin = dp(topMargin);
    return params;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
