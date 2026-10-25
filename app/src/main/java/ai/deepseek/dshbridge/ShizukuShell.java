package ai.deepseek.dshbridge;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

final class ShizukuShell {
  static final int PERMISSION_REQUEST = 1002;

  private ShizukuShell() {
  }

  static boolean isRunning() {
    try {
      return Shizuku.pingBinder();
    } catch (Throwable ignored) {
      return false;
    }
  }

  static boolean isGranted() {
    try {
      return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable ignored) {
      return false;
    }
  }

  static boolean requestPermission() {
    try {
      Shizuku.requestPermission(PERMISSION_REQUEST);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  static Result run(String command, int timeoutSeconds) {
    if (!isRunning()) return new Result(-1, "Shizuku service is not running");
    if (!isGranted()) return new Result(-1, "Shizuku permission is not granted");
    try {
      Method newProcess = Shizuku.class.getDeclaredMethod(
          "newProcess", String[].class, String[].class, String.class);
      newProcess.setAccessible(true);
      Process process = (Process) newProcess.invoke(
          null, new String[]{"sh", "-c", command}, null, null);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread stdout = new Thread(() -> drain(process.getInputStream(), output));
      Thread stderr = new Thread(() -> drain(process.getErrorStream(), output));
      stdout.start();
      stderr.start();
      boolean finished = process instanceof ShizukuRemoteProcess
          ? ((ShizukuRemoteProcess) process).waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)
          : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) process.destroy();
      stdout.join(1000);
      stderr.join(1000);
      return new Result(finished ? process.exitValue() : -1, output.toString("UTF-8"));
    } catch (Throwable error) {
      return new Result(-1, error.getClass().getSimpleName() + ": " + error.getMessage());
    }
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

  static final class Result {
    final int code;
    final String output;

    Result(int code, String output) {
      this.code = code;
      this.output = output == null ? "" : output.trim();
    }

    boolean ok() {
      return code == 0;
    }
  }
}
