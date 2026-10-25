package ai.deepseek.dshbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DshLauncher {
  static final int ROOT_PORT = 3080;
  private static final String ROOT_HOME = "/data/data/com.termux/files/home";
  private static final String ROOT_LOG = ROOT_HOME + "/dsh-root.log";
  private static final String ROOT_SCRIPT = "/data/local/tmp/start-dsh-root.sh";
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s'\"]+");

  interface Callback {
    void onProgress(String log);

    void onResult(String url, String error);
  }

  private DshLauncher() {
  }

  static void startRoot(final Callback callback) {
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
                      + "DSH_WEB_HOST=127.0.0.1 DSH_WEB_PORT=" + ROOT_PORT + " "
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
                  if (url != null && portOpen(ROOT_PORT)) break;
                }
                callback.onProgress(lastLog);
                if (url == null || !portOpen(ROOT_PORT)) {
                  error = "dsh did not start listening on 127.0.0.1:" + ROOT_PORT + ".";
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
    return found;
  }

  private static boolean portOpen(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
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
