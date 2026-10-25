package ai.deepseek.dshbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DshLauncher {
  private static final String ROOT_HOME = "/data/data/com.termux/files/home";
  private static final String ROOT_LOG = ROOT_HOME + "/dsh-root.log";
  private static final String ROOT_SCRIPT = "/data/local/tmp/start-dsh-root.sh";
  private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s'\"]+");

  interface Callback {
    void onProgress(String log);

    void onResult(String url, String error);
  }

  private DshLauncher() {
  }

  static void startRoot(final Callback callback, final String host, final int port) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        String url = null;
        String error = null;
        try {
          CommandResult root = su("id", 10);
          if (!root.ok() || !root.output.contains("uid=0")) {
            error = "Root permission is unavailable.\n" + root.output;
          } else {
            CommandResult script = su("test -f " + ROOT_SCRIPT + " && echo PRESENT", 10);
            if (!script.ok() || !script.output.contains("PRESENT")) {
              error = "Missing " + ROOT_SCRIPT + ".\nRun install.sh first.";
            } else {
              CommandResult start = su(
                  "pkill -f '[.]dsh/android.cordis.yml' 2>/dev/null || true; "
                      + "sleep 1; : > " + ROOT_LOG + "; rm -f " + ROOT_HOME + "/dsh-root.pid; "
                      + "DSH_WEB_HOST=" + shellQuote(host) + " DSH_WEB_PORT=" + port + " "
                      + "sh " + ROOT_SCRIPT,
                  20);
              if (!start.ok()) {
                error = "Failed to start dsh as root.\n" + start.output;
              } else {
                long deadline = System.currentTimeMillis() + 180000;
                String lastLog = "";
                while (System.currentTimeMillis() < deadline) {
                  Thread.sleep(1000);
                  CommandResult tail = su("tail -n 120 " + ROOT_LOG + " 2>/dev/null", 10);
                  lastLog = tail.output;
                  callback.onProgress(lastLog);
                  url = findUrl(lastLog);
                  if (url != null && portOpen(host, port)) break;
                }
                callback.onProgress(lastLog);
                if (url == null || !portOpen(host, port)) {
                  error = "dsh did not start listening on " + clientHost(host) + ":" + port + ".";
                  url = null;
                }
              }
            }
          }
        } catch (Throwable throwable) {
          error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        callback.onResult(url, error);
      }
    }, "dsh-root-start").start();
  }

  static void startNoRoot(final Context context, final Callback callback, final String host, final int port) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        String url = null;
        String error = null;
        try {
          if (!ShizukuShell.isRunning()) {
            error = "Shizuku service is not running.";
          } else if (!ShizukuShell.isGranted()) {
            ShizukuShell.requestPermission();
            error = "Grant Shizuku permission to DSH Bridge, then tap Start again.";
          } else if (context.checkSelfPermission(TERMUX_PERMISSION)
              != PackageManager.PERMISSION_GRANTED) {
            ShizukuShell.Result grant = ShizukuShell.run(
                "pm grant " + context.getPackageName() + " " + TERMUX_PERMISSION, 15);
            if (context.checkSelfPermission(TERMUX_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
              error = "Termux RUN_COMMAND permission is not granted.\n" + grant.output;
            }
          }
          if (error == null) {
            clearDshUrl();
            try {
              sendTermuxCommand(context, noRootStartCommand(host, port));
            } catch (Throwable throwable) {
              error = "Could not start Termux command.\n"
                  + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            }
          }
          if (error == null) {
            long deadline = System.currentTimeMillis() + 180000;
            while (System.currentTimeMillis() < deadline) {
              url = fetchDshUrl();
              if (url != null && !url.isEmpty()) break;
              Thread.sleep(1000);
            }
            if (url == null || url.isEmpty()) {
              error = "Timed out waiting for no-root dsh.\n"
                  + "Check Termux allow-external-apps and ~/dsh.log.";
              url = null;
            }
          }
        } catch (Throwable throwable) {
          error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        callback.onResult(url, error);
      }
    }, "dsh-shizuku-start").start();
  }

  static void stopNoRoot(final Context context, final Callback callback) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        String error = null;
        try {
          if (context.checkSelfPermission(TERMUX_PERMISSION)
              != PackageManager.PERMISSION_GRANTED) {
            error = "Termux RUN_COMMAND permission is not granted.";
          } else {
            sendTermuxCommand(context,
                "pkill -f '[.]dsh-noroot/android.cordis.yml' 2>/dev/null || true\n");
            clearDshUrl();
          }
        } catch (Throwable throwable) {
          error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        callback.onResult("", error);
      }
    }, "dsh-shizuku-stop").start();
  }

  private static void sendTermuxCommand(Context context, String command) {
    Intent intent = new Intent("com.termux.RUN_COMMAND");
    intent.setComponent(new ComponentName(
        "com.termux", "com.termux.app.RunCommandService"));
    intent.putExtra("com.termux.RUN_COMMAND_PATH",
        "/data/data/com.termux/files/usr/bin/bash");
    intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
    intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", ROOT_HOME);
    intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
    context.startService(intent);
  }

  private static String noRootStartCommand(String host, int port) {
    return "pkill -f '[.]dsh-noroot/android.cordis.yml' 2>/dev/null || true\n"
        + "sleep 1\n"
        + ": > \"$HOME/dsh.log\"\n"
        + "[ -f \"$HOME/.dsh-noroot/start-dsh.sh\" ] || exit 2\n"
        + "DSH_WEB_HOST=" + shellQuote(host) + " DSH_WEB_PORT=" + port
        + " bash \"$HOME/.dsh-noroot/start-dsh.sh\"\n"
        + "i=0\n"
        + "while [ \"$i\" -lt 90 ]; do\n"
        + "  LINE=$(grep 'dsh web:' \"$HOME/dsh.log\" 2>/dev/null | tail -1)\n"
        + "  TOKEN=$(printf '%s' \"$LINE\" | sed -n 's/.*token=\\([A-Za-z0-9_-]*\\).*/\\1/p')\n"
        + "  if [ -n \"$TOKEN\" ]; then\n"
        + "    curl -fsS -X POST --data-binary \"http://" + clientHost(host) + ":" + port
        + "/?token=$TOKEN\" \"http://127.0.0.1:17654/dsh/register\""
        + " >/dev/null 2>&1 || true\n"
        + "    exit 0\n"
        + "  fi\n"
        + "  sleep 2\n"
        + "  i=$((i+1))\n"
        + "done\n"
        + "exit 1\n";
  }

  private static void clearDshUrl() {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL("http://127.0.0.1:17654/dsh/clear").openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(1000);
      connection.setReadTimeout(1000);
      connection.getOutputStream().close();
      connection.getResponseCode();
    } catch (IOException ignored) {
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static String fetchDshUrl() {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL("http://127.0.0.1:17654/dsh/status").openConnection();
      connection.setConnectTimeout(1000);
      connection.setReadTimeout(1000);
      if (connection.getResponseCode() != 200) return "";
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      drain(connection.getInputStream(), output);
      JSONObject json = new JSONObject(output.toString("UTF-8"));
      return json.optString("url", "");
    } catch (Throwable ignored) {
      return "";
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  static void stopRoot(final Callback callback) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        String error = null;
        try {
          CommandResult result = su(
              "pkill -f '[.]dsh/android.cordis.yml' 2>/dev/null || true", 10);
          if (!result.ok()) error = "Failed to stop dsh.\n" + result.output;
        } catch (Throwable throwable) {
          error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        callback.onResult("", error);
      }
    }, "dsh-root-stop").start();
  }

  private static CommandResult su(String command, int timeoutSeconds) throws Exception {
    Process process = new ProcessBuilder("su", "-c", command)
        .redirectErrorStream(true)
        .start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Thread reader = new Thread(new Runnable() {
      @Override
      public void run() {
        drain(process.getInputStream(), output);
      }
    }, "dsh-su-output");
    reader.start();
    boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    if (!finished) {
      process.destroy();
      reader.join(1000);
      return new CommandResult(-1, output.toString("UTF-8") + "\ntimed out");
    }
    reader.join(1500);
    return new CommandResult(process.exitValue(), output.toString("UTF-8"));
  }

  private static void drain(InputStream input, ByteArrayOutputStream output) {
    try {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = input.read(buffer)) != -1) {
        synchronized (output) {
          output.write(buffer, 0, read);
        }
      }
    } catch (IOException ignored) {
    }
  }

  private static String findUrl(String output) {
    String found = null;
    for (String line : output.split("\\r?\\n")) {
      Matcher matcher = URL_PATTERN.matcher(line);
      while (matcher.find()) found = matcher.group();
    }
    return clientUrl(found);
  }

  private static String clientHost(String host) {
    if ("0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) {
      return "127.0.0.1";
    }
    return host;
  }

  private static String clientUrl(String url) {
    if (url == null) return null;
    return url.replaceFirst("^http://0\\.0\\.0\\.0:", "http://127.0.0.1:")
        .replaceFirst("^http://\\[::\\]:", "http://127.0.0.1:");
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static boolean portOpen(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(clientHost(host), port), 800);
      return true;
    } catch (IOException ignored) {
      return false;
    }
  }

  private static final class CommandResult {
    final int code;
    final String output;

    CommandResult(int code, String output) {
      this.code = code;
      this.output = output == null ? "" : output.trim();
    }

    boolean ok() {
      return code == 0;
    }
  }
}
