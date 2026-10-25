package ai.deepseek.dshbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
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
  private static final String PREF_PORT = "port";
  private static final String PREF_HOST = "host";
  private static final String PREF_MODE = "mode";

  private TextView status;
  private Button startRoot;
  private Button startShizuku;
  private Button stopDsh;
  private Button openDsh;
  private Button copyUrl;
  private Button testBridge;
  private EditText hostInput;
  private EditText portInput;
  private SharedPreferences preferences;
  private String dshUrl = "";
  private String dshMode = "";
  private String lastLog = "";
  private boolean dshBusy;
  private int statusEpoch;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
    dshUrl = preferences.getString(PREF_URL, "");
    dshMode = preferences.getString(PREF_MODE, "");
    int defaultPort = 3080;
    String defaultHost = "127.0.0.1";
    Uri storedUrl = Uri.parse(dshUrl);
    if (storedUrl.getPort() > 0) defaultPort = storedUrl.getPort();
    if (storedUrl.getHost() != null && !storedUrl.getHost().isEmpty()) {
      defaultHost = storedUrl.getHost();
    }

    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(getColor(R.color.app_background));
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(20), dp(20), dp(20));
    root.setBackgroundColor(getColor(R.color.app_background));

    TextView title = new TextView(this);
    title.setText("DSH Accessibility Bridge");
    title.setTextSize(24);
    title.setTextColor(getColor(R.color.title_color));
    title.setGravity(Gravity.START);
    root.addView(title, matchWrap());

    TextView hostLabel = new TextView(this);
    hostLabel.setText("Web UI host");
    hostLabel.setTextSize(14);
    hostLabel.setTextColor(getColor(R.color.label_color));
    hostLabel.setPadding(0, dp(16), 0, 0);
    root.addView(hostLabel, matchWrap());

    hostInput = new EditText(this);
    hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    hostInput.setSingleLine(true);
    hostInput.setSelectAllOnFocus(true);
    hostInput.setTextColor(getColor(R.color.text_primary));
    hostInput.setText(preferences.getString(PREF_HOST, defaultHost));
    root.addView(hostInput, matchWrap());

    TextView portLabel = new TextView(this);
    portLabel.setText("Web UI port");
    portLabel.setTextSize(14);
    portLabel.setTextColor(getColor(R.color.label_color));
    portLabel.setPadding(0, dp(12), 0, 0);
    root.addView(portLabel, matchWrap());

    portInput = new EditText(this);
    portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
    portInput.setSingleLine(true);
    portInput.setSelectAllOnFocus(true);
    portInput.setTextColor(getColor(R.color.text_primary));
    portInput.setText(preferences.getString(PREF_PORT, String.valueOf(defaultPort)));
    root.addView(portInput, matchWrap());

    startRoot = new Button(this);
    startRoot.setText("Start DSH (root)");
    startRoot.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        startRootDsh();
      }
    });
    root.addView(startRoot, matchWrap());

    startShizuku = new Button(this);
    startShizuku.setText("Start DSH (Shizuku)");
    startShizuku.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        startNoRootDsh();
      }
    });
    root.addView(startShizuku, matchWrap());

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

    testBridge = new Button(this);
    testBridge.setText("Test bridge connection");
    testBridge.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        refreshStatus();
      }
    });
    root.addView(testBridge, matchWrap());

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
    status.setTextColor(getColor(R.color.terminal_text));
    status.setBackgroundColor(getColor(R.color.terminal_background));
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
    final String host = readHost();
    if (host == null) return;
    final int port = readPort();
    if (port < 0) return;
    dshBusy = true;
    statusEpoch++;
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
              dshMode = "root";
              preferences.edit()
                  .putString(PREF_URL, dshUrl)
                  .putString(PREF_MODE, dshMode)
                  .apply();
              status.setText(lastLog.isEmpty() ? url : lastLog);
            }
            updateDshButtons();
          }
        });
      }
    }, host, port);
  }

  private void startNoRootDsh() {
    final String host = readHost();
    if (host == null) return;
    final int port = readPort();
    if (port < 0) return;
    dshBusy = true;
    statusEpoch++;
    lastLog = "";
    updateDshButtons();
    status.setText("Starting dsh through Shizuku/Termux...");
    DshLauncher.startNoRoot(this, new DshLauncher.Callback() {
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
              status.setText(lastLog.isEmpty()
                  ? "dsh did not return a Web UI URL." : lastLog);
            } else {
              dshUrl = url;
              dshMode = "noroot";
              preferences.edit()
                  .putString(PREF_URL, dshUrl)
                  .putString(PREF_MODE, dshMode)
                  .apply();
              status.setText(lastLog.isEmpty()
                  ? "dsh is running through Shizuku/Termux.\n" + dshUrl : lastLog);
            }
            updateDshButtons();
          }
        });
      }
    }, host, port);
  }

  private void stopDsh() {
    dshBusy = true;
    statusEpoch++;
    updateDshButtons();
    status.setText("Stopping dsh...");
    DshLauncher.Callback callback = new DshLauncher.Callback() {
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
              dshMode = "";
              lastLog = "";
              preferences.edit().remove(PREF_URL).remove(PREF_MODE).apply();
              status.setText("dsh stopped.");
            }
            updateDshButtons();
          }
        });
      }
    };
    if ("noroot".equals(dshMode)) {
      DshLauncher.stopNoRoot(this, callback);
    } else {
      DshLauncher.stopRoot(callback);
    }
  }

  private String readHost() {
    String host = hostInput.getText().toString().trim();
    if (!host.matches("[A-Za-z0-9._:\\[\\]-]+")) {
      status.setText("Enter a valid Web UI host.");
      return null;
    }
    preferences.edit().putString(PREF_HOST, host).apply();
    return host;
  }

  private int readPort() {
    String text = portInput.getText().toString().trim();
    try {
      int port = Integer.parseInt(text);
      if (port >= 1 && port <= 65535) {
        preferences.edit().putString(PREF_PORT, String.valueOf(port)).apply();
        return port;
      }
    } catch (NumberFormatException ignored) {
    }
    status.setText("Enter a valid Web UI port (1-65535).");
    return -1;
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
    startShizuku.setEnabled(!dshBusy);
    stopDsh.setEnabled(!dshBusy);
    testBridge.setEnabled(!dshBusy);
    hostInput.setEnabled(!dshBusy);
    portInput.setEnabled(!dshBusy);
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
    if (dshBusy) return;
    final int epoch = ++statusEpoch;
    status.setText("Checking 127.0.0.1:17654 ...");
    new Thread(new Runnable() {
      @Override
      public void run() {
        final String message = probe();
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if (epoch != statusEpoch || dshBusy) return;
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
