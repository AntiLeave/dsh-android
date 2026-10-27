package ai.deepseek.dshbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Surface;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * AccessibilityService that exposes the current screen tree and input actions
 * over a loopback HTTP API for the DeepSeek Harness process running in Termux.
 *
 * The service is intentionally self-contained: no third-party libraries and no
 * root requirement. The dsh tool plugin talks to it at 127.0.0.1:17654.
 */
public final class BridgeService extends AccessibilityService {
  private static final String TAG = "DshBridge";
  private static final int PORT = 17654;
  private static final int MAX_BODY_BYTES = 1 << 20;
  private static final int EVENT_RING = 120;
  private static final int SHIZUKU_PERMISSION_REQUEST = 1001;
  private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
  private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;

  private final Object lock = new Object();
  private final List<Entry> entries = new ArrayList<>();
  private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
  private volatile boolean running;
  private ServerSocket serverSocket;
  private ExecutorService pool;
  private long stateSeq;
  private long eventSeq;
  private volatile long lastEventAt;
  private volatile String currentPackage = "";
  private volatile String currentActivity = "";
  private volatile String dshUrl = "";
  private volatile String dshLog = "";
  private volatile boolean dshFailed;
  private final Map<Integer, String> activities = new ConcurrentHashMap<>();
  private int activeDisplayId = Display.DEFAULT_DISPLAY;
  private final Object virtualDisplayLock = new Object();
  private VirtualDisplay virtualDisplay;
  private ImageReader virtualDisplayReader;
  private int virtualDisplayId = -1;
  private String virtualDisplayName = "";

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    startServer();
    Log.i(TAG, "accessibility bridge connected on 127.0.0.1:" + PORT);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    lastEventAt = SystemClock.elapsedRealtime();
    String eventPackage = safe(event.getPackageName());
    if (getPackageName().equals(eventPackage)) return;
    int displayId = event.getDisplayId();
    if (!eventPackage.isEmpty()) currentPackage = eventPackage;
    if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && event.getClassName() != null) {
      String activity = event.getClassName().toString();
      activities.put(displayId, activity);
      if (displayId == Display.DEFAULT_DISPLAY) currentActivity = activity;
    }
    try {
      JSONObject item = new JSONObject();
      item.put("seq", ++eventSeq);
      item.put("time", System.currentTimeMillis());
      item.put("type", AccessibilityEvent.eventTypeToString(event.getEventType()));
      item.put("displayId", displayId);
      item.put("package", safe(event.getPackageName()));
      item.put("class", safe(event.getClassName()));
      item.put("text", join(event.getText()));
      item.put("contentDescription", safe(event.getContentDescription()));
      synchronized (events) {
        events.addLast(item);
        while (events.size() > EVENT_RING) events.removeFirst();
      }
    } catch (JSONException ignored) {
      // Event telemetry is best effort only.
    }
  }

  @Override
  public void onInterrupt() {
    // Nothing to interrupt; every operation is request-scoped.
  }

  @Override
  public void onDestroy() {
    stopServer();
    synchronized (lock) {
      recycleEntriesLocked();
    }
    synchronized (virtualDisplayLock) {
      releaseVirtualDisplayLocked();
    }
    super.onDestroy();
    Log.i(TAG, "accessibility bridge stopped");
  }

  // ---------------------------------------------------------------------------
  // HTTP server

  private void startServer() {
    if (running) return;
    running = true;
    pool = Executors.newCachedThreadPool(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "dsh-bridge-http");
        thread.setDaemon(true);
        return thread;
      }
    });
    Thread acceptThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          serverSocket = new ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"));
          serverSocket.setReuseAddress(true);
          while (running) {
            final Socket socket = serverSocket.accept();
            pool.execute(new Runnable() {
              @Override
              public void run() {
                handleConnection(socket);
              }
            });
          }
        } catch (IOException error) {
          if (running) Log.e(TAG, "bridge server failed: " + error);
        }
      }
    }, "dsh-bridge-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  private void stopServer() {
    running = false;
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {
    }
    if (pool != null) {
      pool.shutdownNow();
      try {
        pool.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void handleConnection(Socket socket) {
    try {
      socket.setSoTimeout(20000);
      InputStream input = socket.getInputStream();
      ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
      int matched = 0;
      int value;
      while ((value = input.read()) != -1 && headerBuffer.size() < 65536) {
        headerBuffer.write(value);
        if (matched == 0 && value == '\r') matched = 1;
        else if (matched == 1 && value == '\n') matched = 2;
        else if (matched == 2 && value == '\r') matched = 3;
        else if (matched == 3 && value == '\n') {
          headerBuffer.write(-1);
          break;
        } else matched = 0;
      }
      byte[] headerBytes = headerBuffer.toByteArray();
      int headerLength = headerBytes.length;
      if (headerLength > 0 && headerBytes[headerLength - 1] == (byte) -1) {
        headerLength -= 1;
        headerBytes = java.util.Arrays.copyOf(headerBytes, headerLength);
      }
      String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
      String[] lines = headerText.split("\r\n");
      if (lines.length == 0 || lines[0].isEmpty()) {
        writeResponse(socket, 400, errorJson("empty request"));
        return;
      }
      String[] requestLine = lines[0].split(" ");
      if (requestLine.length < 2) {
        writeResponse(socket, 400, errorJson("malformed request line"));
        return;
      }
      String method = requestLine[0];
      String target = requestLine[1];
      int contentLength = 0;
      String contentType = "";
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        if (colon < 0) continue;
        String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String headerValue = lines[i].substring(colon + 1).trim();
        if (name.equals("content-length")) {
          try {
            contentLength = Integer.parseInt(headerValue);
          } catch (NumberFormatException ignored) {
          }
        } else if (name.equals("content-type")) {
          contentType = headerValue;
        }
      }
      if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
        writeResponse(socket, 413, errorJson("request body too large"));
        return;
      }
      byte[] body = new byte[contentLength];
      int offset = 0;
      while (offset < contentLength) {
        int read = input.read(body, offset, contentLength - offset);
        if (read < 0) break;
        offset += read;
      }
      Uri uri = Uri.parse(target);
      String path = uri.getPath() == null ? "/" : uri.getPath();
      JSONObject response;
      try {
        response = dispatch(method, path, uri, body);
      } catch (Exception error) {
        Log.w(TAG, "request failed: " + path, error);
        response = errorJson(error.getClass().getSimpleName() + ": " + error.getMessage());
      }
      writeResponse(socket, response.optBoolean("ok", false) ? 200 : 400, response);
    } catch (IOException error) {
      Log.w(TAG, "connection failed", error);
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private JSONObject dispatch(String method, String path, Uri uri, byte[] body) throws Exception {
    if (path.equals("/") || path.equals("/health")) {
      return health();
    }
    if (path.equals("/dsh/status")) {
      return dshStatus();
    }
    if (path.equals("/dsh/clear") && method.equals("POST")) {
      dshUrl = "";
      dshLog = "";
      dshFailed = false;
      return dshStatus();
    }
    if (path.equals("/dsh/register") && method.equals("POST")) {
      return registerDsh(body);
    }
    if (path.equals("/dsh/log") && method.equals("POST")) {
      dshLog = new String(body, StandardCharsets.UTF_8).trim();
      JSONObject json = new JSONObject();
      json.put("ok", true);
      return json;
    }
    if (path.equals("/dsh/fail") && method.equals("POST")) {
      dshFailed = true;
      return dshStatus();
    }
    if (path.equals("/shizuku")) {
      if (method.equals("POST")) {
        String text = new String(body, StandardCharsets.UTF_8);
        JSONObject command = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        return shizukuAction(command);
      }
      return shizukuStatus();
    }
    if (path.equals("/shell") && method.equals("POST")) {
      String text = new String(body, StandardCharsets.UTF_8);
      JSONObject command = text.isEmpty() ? new JSONObject() : new JSONObject(text);
      return runShizuku(command.optString("command", ""));
    }
    if (path.equals("/virtualdisplay")) {
      if (method.equals("POST")) {
        String text = new String(body, StandardCharsets.UTF_8);
        JSONObject command = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        return virtualDisplayAction(command);
      }
      return virtualDisplayStatus();
    }
    if (path.equals("/displays")) {
      return displays();
    }
    if (path.equals("/state")) {
      int maxDepth = queryInt(uri, "maxDepth", 28);
      int maxElements = queryInt(uri, "maxElements", 600);
      int displayId = queryInt(uri, "displayId", Display.DEFAULT_DISPLAY);
      return state(Math.max(1, Math.min(80, maxDepth)), Math.max(1, Math.min(3000, maxElements)), displayId);
    }
    if (path.equals("/screenshot")) {
      int maxWidth = queryInt(uri, "maxWidth", 720);
      int quality = queryInt(uri, "quality", 80);
      int displayId = queryInt(uri, "displayId", Display.DEFAULT_DISPLAY);
      return screenshot(Math.max(1, Math.min(2160, maxWidth)), Math.max(20, Math.min(100, quality)), displayId);
    }
    if (path.equals("/apps")) {
      return apps();
    }
    if (path.equals("/events")) {
      long since = 0;
      String raw = uri.getQueryParameter("since");
      if (raw != null) {
        try {
          since = Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
        }
      }
      return eventList(since);
    }
    if (path.equals("/action") && method.equals("POST")) {
      String text = new String(body, StandardCharsets.UTF_8);
      JSONObject command = text.isEmpty() ? new JSONObject() : new JSONObject(text);
      return action(command);
    }
    return errorJson("unknown endpoint " + path);
  }

  private void writeResponse(Socket socket, int status, JSONObject body) throws IOException {
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    String reason = status == 200 ? "OK" : status == 413 ? "Payload Too Large" : "Bad Request";
    String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
        + "Content-Type: application/json; charset=utf-8\r\n"
        + "Content-Length: " + bytes.length + "\r\n"
        + "Connection: close\r\n"
        + "Cache-Control: no-store\r\n\r\n";
    OutputStream output = new BufferedOutputStream(socket.getOutputStream());
    output.write(header.getBytes(StandardCharsets.ISO_8859_1));
    output.write(bytes);
    output.flush();
  }

  private static JSONObject errorJson(String message) {
    JSONObject json = new JSONObject();
    try {
      json.put("ok", false);
      json.put("error", message == null ? "unknown error" : message);
    } catch (JSONException ignored) {
    }
    return json;
  }

  private JSONObject dshStatus() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("ok", true);
    json.put("url", dshUrl);
    json.put("log", dshLog);
    json.put("failed", dshFailed);
    return json;
  }

  private JSONObject registerDsh(byte[] body) throws JSONException {
    String url = new String(body, StandardCharsets.UTF_8).trim();
    Uri parsed = Uri.parse(url);
    String scheme = parsed.getScheme();
    if (parsed.getHost() == null
        || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
      return errorJson("invalid dsh url");
    }
    dshUrl = url;
    dshFailed = false;
    JSONObject json = new JSONObject();
    json.put("ok", true);
    return json;
  }

  private JSONObject health() throws JSONException {
    JSONObject json = new JSONObject();
    DisplayMetrics metrics = getResources().getDisplayMetrics();
    json.put("ok", true);
    json.put("service", "dsh-accessibility-bridge");
    json.put("sdk", Build.VERSION.SDK_INT);
    json.put("port", PORT);
    json.put("package", currentPackage);
    json.put("activity", currentActivity);
    json.put("screenWidth", metrics.widthPixels);
    json.put("screenHeight", metrics.heightPixels);
    json.put("densityDpi", metrics.densityDpi);
    json.put("stateSeq", stateSeq);
    json.put("eventSeq", eventSeq);
    json.put("lastEventAt", lastEventAt);
    return json;
  }

  // ---------------------------------------------------------------------------
  // State observation

  private JSONObject state(int maxDepth, int maxElements, int displayId) throws JSONException {
    synchronized (lock) {
      activeDisplayId = displayId;
      JSONObject result = buildStateLocked(maxDepth, maxElements, displayId);
      if (result == null) {
        return errorJson("no active window on display " + displayId
            + "; use /displays to find a valid display");
      }
      return result;
    }
  }

  private JSONObject buildStateLocked(int maxDepth, int maxElements, int displayId) throws JSONException {
    Display display = displayManager().getDisplay(displayId);
    if (display == null) return null;
    AccessibilityNodeInfo root = activeRoot(displayId);
    if (root == null) return null;

    activeDisplayId = displayId;
    recycleEntriesLocked();
    JSONArray nodes = new JSONArray();
    StringBuilder text = new StringBuilder(8192);
    DisplayMetrics metrics = displayMetrics(display);
    String pkg = safe(root.getPackageName());
    if (pkg.isEmpty()) pkg = currentPackage;
    String activity = activities.getOrDefault(displayId, "");

    text.append("# display=").append(displayId).append(" package=").append(pkg)
        .append(" activity=").append(activity)
        .append(" screen=").append(metrics.widthPixels).append('x').append(metrics.heightPixels)
        .append(" density=").append(metrics.densityDpi).append('\n');
    traverseLocked(root, -1, 0, maxDepth, maxElements, nodes, text);

    JSONObject result = new JSONObject();
    result.put("ok", true);
    result.put("seq", ++stateSeq);
    result.put("displayId", displayId);
    result.put("displayName", display.getName());
    result.put("package", pkg);
    result.put("activity", activity);
    result.put("screenWidth", metrics.widthPixels);
    result.put("screenHeight", metrics.heightPixels);
    result.put("densityDpi", metrics.densityDpi);
    result.put("maxDepth", maxDepth);
    result.put("maxElements", maxElements);
    result.put("truncated", nodes.length() >= maxElements);
    result.put("nodes", nodes);
    result.put("text", text.toString());
    result.put("windows", windowList(displayId));
    return result;
  }

  private AccessibilityNodeInfo activeRoot(int displayId) {
    SparseArray<List<AccessibilityWindowInfo>> windowsByDisplay = getWindowsOnAllDisplays();
    List<AccessibilityWindowInfo> windows = windowsByDisplay.get(displayId);
    if (windows == null) {
      if (displayId != Display.DEFAULT_DISPLAY) return null;
      AccessibilityNodeInfo root = getRootInActiveWindow();
      return root != null && !isOwnWindow(root) ? root : null;
    }
    for (AccessibilityWindowInfo window : windows) {
      if (window.isActive() || window.isFocused()) {
        AccessibilityNodeInfo candidate = window.getRoot();
        if (candidate != null && !isOwnWindow(candidate)) return candidate;
      }
    }
    for (int i = windows.size() - 1; i >= 0; i--) {
      AccessibilityNodeInfo candidate = windows.get(i).getRoot();
      if (candidate != null && !isOwnWindow(candidate)) return candidate;
    }
    return null;
  }

  private boolean isOwnWindow(AccessibilityNodeInfo root) {
    return getPackageName().equals(safe(root.getPackageName()));
  }

  private JSONArray windowList(int displayId) throws JSONException {
    JSONArray array = new JSONArray();
    SparseArray<List<AccessibilityWindowInfo>> windowsByDisplay = getWindowsOnAllDisplays();
    List<AccessibilityWindowInfo> windows = windowsByDisplay.get(displayId);
    if (windows == null) return array;
    for (AccessibilityWindowInfo window : windows) {
      AccessibilityNodeInfo root = window.getRoot();
      if (root != null && isOwnWindow(root)) continue;
      JSONObject item = new JSONObject();
      Rect bounds = new Rect();
      window.getBoundsInScreen(bounds);
      item.put("id", window.getId());
      item.put("displayId", window.getDisplayId());
      item.put("type", window.getType());
      item.put("layer", window.getLayer());
      item.put("active", window.isActive());
      item.put("focused", window.isFocused());
      item.put("bounds", boundsArray(bounds));
      CharSequence title = window.getTitle();
      item.put("title", safe(title));
      array.put(item);
    }
    return array;
  }

  private DisplayManager displayManager() {
    return (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
  }

  private static DisplayMetrics displayMetrics(Display display) {
    DisplayMetrics metrics = new DisplayMetrics();
    display.getRealMetrics(metrics);
    return metrics;
  }

  private void traverseLocked(AccessibilityNodeInfo node, int parent, int depth, int maxDepth,
      int maxElements, JSONArray nodes, StringBuilder text) throws JSONException {
    if (nodes.length() >= maxElements) return;
    Entry entry = new Entry(entries.size(), parent, depth, node);
    entries.add(entry);
    nodes.put(entry.toJson());
    text.append(entry.toText()).append('\n');
    if (depth >= maxDepth) return;
    int count = node.getChildCount();
    for (int i = 0; i < count && nodes.length() < maxElements; i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      traverseLocked(child, entry.id, depth + 1, maxDepth, maxElements, nodes, text);
    }
  }

  private void recycleEntriesLocked() {
    for (Entry entry : entries) {
      try {
        entry.node.recycle();
      } catch (RuntimeException ignored) {
      }
    }
    entries.clear();
  }

  private Entry entry(int id) {
    return id >= 0 && id < entries.size() ? entries.get(id) : null;
  }

  private Entry findByText(String text, String desc, String viewId) {
    String needle = text == null ? "" : text;
    String description = desc == null ? "" : desc;
    String idFilter = viewId == null ? "" : viewId;
    Entry partial = null;
    for (Entry entry : entries) {
      if (!idFilter.isEmpty() && !entry.viewId.equals(idFilter)) continue;
      boolean textMatch = !needle.isEmpty() && entry.text.equals(needle);
      boolean descMatch = !description.isEmpty() && entry.desc.equals(description);
      if (textMatch || descMatch) return entry;
      if (partial == null) {
        if (!needle.isEmpty() && entry.text.contains(needle)) partial = entry;
        else if (!description.isEmpty() && entry.desc.contains(description)) partial = entry;
      }
    }
    return partial;
  }

  private Entry findEditable() {
    Entry focused = null;
    for (Entry entry : entries) {
      if (!entry.editable) continue;
      if (entry.focused) return entry;
      if (focused == null) focused = entry;
    }
    return focused;
  }

  private Entry findScrollable(Entry start) {
    Entry current = start;
    for (int i = 0; i < 10 && current != null; i++) {
      if (current.scrollable) return current;
      current = current.parent >= 0 ? entry(current.parent) : null;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Actions

  private JSONObject action(JSONObject command) throws Exception {
    String op = command.optString("op", "");
    if (op.isEmpty()) return errorJson("missing op");
    int displayId = command.optInt("displayId", Display.DEFAULT_DISPLAY);
    synchronized (lock) {
      JSONObject fresh = buildStateLocked(28, 600, displayId);
      if (fresh == null && !op.equals("launch") && !op.equals("openUrl")
          && !op.equals("global") && !op.equals("clipboard")) {
        return errorJson("no active window on display " + displayId);
      }
      if (displayId != Display.DEFAULT_DISPLAY
          && (op.equals("global") || op.equals("back") || op.equals("press"))) {
        return errorJson("global/back actions are not display-scoped; use tap or the android_shell input -d command");
      }
      switch (op) {
        case "state":
          return state(command.optInt("maxDepth", 28), command.optInt("maxElements", 600), displayId);
        case "click":
          return click(command, false);
        case "longClick":
          return click(command, true);
        case "setText":
          return setText(command);
        case "typeText":
          return typeText(command);
        case "clearText":
          return clearText(command);
        case "scroll":
          return scroll(command);
        case "swipe":
          return swipe(command);
        case "tap":
          return tapCommand(command);
        case "longPress":
          return longPressCommand(command);
        case "global":
        case "back":
          return global(command.optString("action", op.equals("back") ? "back" : ""));
        case "launch":
          return launch(command);
        case "openUrl":
          return openUrl(command);
        case "clipboard":
          return clipboard(command);
        case "wait":
          return waitFor(command);
        case "find":
          return findAndAct(command);
        case "press":
          return press(command);
        default:
          return errorJson("unknown op " + op);
      }
    }
  }

  private JSONObject click(JSONObject command, boolean longClick) throws Exception {
    Entry target = resolveTarget(command);
    if (target == null) return errorJson("target not found in the current state; call /state first");
    Entry clickable = clickableEntry(target);
    boolean dispatched = false;
    if (clickable != null) {
      AccessibilityNodeInfo node = clickable.node;
      node.refresh();
      dispatched = node.performAction(longClick
          ? AccessibilityNodeInfo.ACTION_LONG_CLICK
          : AccessibilityNodeInfo.ACTION_CLICK);
    }
    if (!dispatched) {
      Rect bounds = target.bounds;
      dispatched = tapGesture(bounds.centerX(), bounds.centerY(), longClick ? 800 : 60, activeDisplayId);
    }
    return resultJson(dispatched, dispatched ? "dispatched" : "click was not dispatched");
  }

  private Entry clickableEntry(Entry start) {
    Entry current = start;
    for (int i = 0; i < 10 && current != null; i++) {
      if (current.clickable || current.longClickable) return current;
      current = current.parent >= 0 ? entry(current.parent) : null;
    }
    return start;
  }

  private Entry resolveTarget(JSONObject command) {
    int id = command.optInt("id", -1);
    if (id >= 0) {
      Entry entry = entry(id);
      if (entry != null) return entry;
    }
    String text = command.optString("text", "");
    String desc = command.optString("desc", "");
    String viewId = command.optString("viewId", "");
    if (!text.isEmpty() || !desc.isEmpty() || !viewId.isEmpty()) {
      return findByText(text, desc, viewId);
    }
    return null;
  }

  private JSONObject setText(JSONObject command) throws JSONException {
    Entry target = resolveTarget(command);
    if (target == null) target = findEditable();
    if (target == null) return errorJson("no editable node; pass id or focus a text field first");
    if (!target.editable) {
      Entry editable = findEditable();
      if (editable != null) target = editable;
    }
    String value = command.optString("text", "");
    AccessibilityNodeInfo node = target.node;
    node.refresh();
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    Bundle args = new Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
    boolean dispatched = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    if (!dispatched) {
      try {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("dsh", value));
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        dispatched = true;
      } catch (RuntimeException ignored) {
      }
    }
    return resultJson(dispatched, dispatched ? "text set" : "ACTION_SET_TEXT was rejected");
  }

  private JSONObject typeText(JSONObject command) throws JSONException {
    Entry target = findEditable();
    if (target == null) return errorJson("no focused editable node");
    JSONObject copy = new JSONObject();
    copy.put("id", target.id);
    copy.put("text", command.optString("text", ""));
    return setText(copy);
  }

  private JSONObject clearText(JSONObject command) throws JSONException {
    JSONObject copy = new JSONObject(command.toString());
    copy.put("text", "");
    return setText(copy);
  }

  private JSONObject scroll(JSONObject command) throws JSONException {
    Entry target = resolveTarget(command);
    if (target == null) {
      target = findScrollable(findEditable());
    }
    if (target == null) {
      for (Entry entry : entries) {
        if (entry.scrollable) {
          target = entry;
          break;
        }
      }
    }
    String direction = command.optString("direction", "down").toLowerCase(Locale.ROOT);
    boolean dispatched = false;
    if (target != null) {
      Entry scrollable = target;
      if (!scrollable.scrollable) {
        Entry ancestor = findScrollable(target);
        if (ancestor != null) scrollable = ancestor;
      }
      if (scrollable.scrollable) {
        AccessibilityNodeInfo node = scrollable.node;
        node.refresh();
        dispatched = node.performAction(direction.equals("up") || direction.equals("backward")
            ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
      }
      if (!dispatched) {
        Rect bounds = scrollable.bounds;
        dispatched = swipeGesture(bounds.centerX(), direction.equals("up") || direction.equals("backward")
            ? (int) (bounds.top + bounds.height() * 0.3)
            : (int) (bounds.top + bounds.height() * 0.7),
            bounds.centerX(), direction.equals("up") || direction.equals("backward")
            ? (int) (bounds.top + bounds.height() * 0.7)
            : (int) (bounds.top + bounds.height() * 0.3), 280, activeDisplayId);
      }
    }
    return resultJson(dispatched, dispatched ? "scroll dispatched" : "no scrollable node and gesture failed");
  }

  private JSONObject swipe(JSONObject command) throws JSONException {
    int fromX = command.optInt("fromX", command.optInt("x1", -1));
    int fromY = command.optInt("fromY", command.optInt("y1", -1));
    int toX = command.optInt("toX", command.optInt("x2", -1));
    int toY = command.optInt("toY", command.optInt("y2", -1));
    int duration = command.optInt("duration", command.optInt("durationMs", 300));
    boolean dispatched = swipeGesture(fromX, fromY, toX, toY, Math.max(20, Math.min(5000, duration)), activeDisplayId);
    return resultJson(dispatched, dispatched ? "swipe dispatched" : "swipe rejected");
  }

  private JSONObject tapCommand(JSONObject command) throws JSONException {
    int x = command.optInt("x", -1);
    int y = command.optInt("y", -1);
    int duration = command.optInt("duration", 60);
    boolean dispatched = tapGesture(x, y, Math.max(20, Math.min(5000, duration)), activeDisplayId);
    return resultJson(dispatched, dispatched ? "tap dispatched" : "tap rejected");
  }

  private JSONObject longPressCommand(JSONObject command) throws JSONException {
    int x = command.optInt("x", -1);
    int y = command.optInt("y", -1);
    boolean dispatched = tapGesture(x, y, 800, activeDisplayId);
    return resultJson(dispatched, dispatched ? "long press dispatched" : "long press rejected");
  }

  private boolean tapGesture(int x, int y, int duration, int displayId) {
    if (x < 0 || y < 0) return false;
    Path path = new Path();
    path.moveTo(x, y);
    path.lineTo(x + 1, y + 1);
    return dispatchPath(path, duration, displayId);
  }

  private boolean swipeGesture(int fromX, int fromY, int toX, int toY, int duration, int displayId) {
    if (fromX < 0 || fromY < 0 || toX < 0 || toY < 0) return false;
    Path path = new Path();
    path.moveTo(fromX, fromY);
    path.lineTo(toX, toY);
    return dispatchPath(path, duration, displayId);
  }

  private boolean dispatchPath(Path path, int duration, int displayId) {
    GestureDescription.StrokeDescription stroke =
        new GestureDescription.StrokeDescription(path, 0, duration);
    GestureDescription gesture = new GestureDescription.Builder()
        .addStroke(stroke).setDisplayId(displayId).build();
    return dispatchGesture(gesture, new GestureResultCallback() {
      @Override
      public void onCompleted(GestureDescription gestureDescription) {
      }

      @Override
      public void onCancelled(GestureDescription gestureDescription) {
      }
    }, null);
  }

  private JSONObject global(String action) throws JSONException {
    int code = -1;
    switch (action.toLowerCase(Locale.ROOT)) {
      case "back":
        code = GLOBAL_ACTION_BACK;
        break;
      case "home":
        code = GLOBAL_ACTION_HOME;
        break;
      case "recents":
      case "recent":
        code = GLOBAL_ACTION_RECENTS;
        break;
      case "notifications":
      case "notification_shade":
        code = GLOBAL_ACTION_NOTIFICATIONS;
        break;
      case "quick_settings":
        code = GLOBAL_ACTION_QUICK_SETTINGS;
        break;
      case "power_dialog":
        code = GLOBAL_ACTION_POWER_DIALOG;
        break;
      default:
        return errorJson("unknown global action " + action);
    }
    boolean dispatched = performGlobalAction(code);
    return resultJson(dispatched, dispatched ? "global action dispatched" : "global action rejected");
  }

  private JSONObject launch(JSONObject command) throws JSONException {
    PackageManager manager = getPackageManager();
    String packageName = command.optString("package", "");
    String app = command.optString("app", "");
    if (packageName.isEmpty() && !app.isEmpty()) {
      packageName = findPackageByLabel(manager, app);
    }
    if (packageName.isEmpty()) return errorJson("package or app is required");
    Intent intent = manager.getLaunchIntentForPackage(packageName);
    if (intent == null) return errorJson("no launch intent for " + packageName);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    int displayId = command.optInt("displayId", Display.DEFAULT_DISPLAY);
    try {
      if (displayId == Display.DEFAULT_DISPLAY) {
        startActivity(intent);
      } else {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        startActivity(intent, options.toBundle());
      }
      return resultJson(true, "launched " + packageName + " on display " + displayId);
    } catch (RuntimeException error) {
      if (displayId != Display.DEFAULT_DISPLAY && shizukuRunning() && shizukuGranted()) {
        JSONObject shell = runShizuku("am start --display " + displayId
            + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
            + shellQuote(packageName));
        if (shell.optBoolean("ok", false) && shell.optInt("code", 1) == 0) {
          return resultJson(true, "launched " + packageName + " on display " + displayId + " via Shizuku");
        }
        return errorJson("launch failed: " + error + "; Shizuku: "
            + shell.optString("output", shell.optString("error", "")));
      }
      return errorJson("launch failed: " + error);
    }
  }

  private String findPackageByLabel(PackageManager manager, String label) {
    List<ApplicationInfo> apps = manager.getInstalledApplications(PackageManager.GET_META_DATA);
    for (ApplicationInfo info : apps) {
      CharSequence appLabel = manager.getApplicationLabel(info);
      if (appLabel != null && appLabel.toString().equalsIgnoreCase(label)) return info.packageName;
    }
    for (ApplicationInfo info : apps) {
      CharSequence appLabel = manager.getApplicationLabel(info);
      if (appLabel != null && appLabel.toString().toLowerCase(Locale.ROOT)
          .contains(label.toLowerCase(Locale.ROOT))) return info.packageName;
    }
    return "";
  }

  private JSONObject openUrl(JSONObject command) throws JSONException {
    String url = command.optString("url", "");
    if (url.isEmpty()) return errorJson("url is required");
    int displayId = command.optInt("displayId", Display.DEFAULT_DISPLAY);
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (displayId == Display.DEFAULT_DISPLAY) {
        startActivity(intent);
      } else {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        startActivity(intent, options.toBundle());
      }
      return resultJson(true, "opened " + url + " on display " + displayId);
    } catch (RuntimeException error) {
      if (displayId != Display.DEFAULT_DISPLAY && shizukuRunning() && shizukuGranted()) {
        JSONObject shell = runShizuku("am start --display " + displayId
            + " -a android.intent.action.VIEW -d " + shellQuote(url));
        if (shell.optBoolean("ok", false) && shell.optInt("code", 1) == 0) {
          return resultJson(true, "opened " + url + " on display " + displayId + " via Shizuku");
        }
        return errorJson("open url failed: " + error + "; Shizuku: "
            + shell.optString("output", shell.optString("error", "")));
      }
      return errorJson("open url failed: " + error);
    }
  }

  private JSONObject clipboard(JSONObject command) throws JSONException {
    String text = command.optString("text", "");
    ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    manager.setPrimaryClip(ClipData.newPlainText("dsh", text));
    JSONObject result = resultJson(true, "clipboard set");
    result.put("text", text);
    return result;
  }

  private JSONObject waitFor(JSONObject command) throws Exception {
    String text = command.optString("text", "");
    String packageName = command.optString("package", "");
    long timeout = Math.max(100, Math.min(60000, command.optLong("timeoutMs", 8000)));
    int displayId = command.optInt("displayId", Display.DEFAULT_DISPLAY);
    long deadline = System.currentTimeMillis() + timeout;
    while (System.currentTimeMillis() < deadline) {
      JSONObject current = buildStateLocked(28, 800, displayId);
      if (current != null) {
        boolean textMatch = text.isEmpty();
        if (!text.isEmpty()) {
          for (Entry entry : entries) {
            if (entry.text.contains(text) || entry.desc.contains(text)) {
              textMatch = true;
              break;
            }
          }
        }
        boolean packageMatch = packageName.isEmpty() || packageName.equals(safe(current.optString("package")));
        if (textMatch && packageMatch) {
          JSONObject result = resultJson(true, "condition met");
          result.put("seq", current.optLong("seq"));
          return result;
        }
      }
      Thread.sleep(250);
    }
    return errorJson("timed out waiting for " + (packageName.isEmpty() ? text : packageName));
  }

  private JSONObject findAndAct(JSONObject command) throws Exception {
    Entry target = findByText(command.optString("text", ""), command.optString("desc", ""),
        command.optString("viewId", ""));
    if (target == null) return errorJson("no matching node");
    JSONObject result = new JSONObject();
    result.put("ok", true);
    result.put("id", target.id);
    result.put("text", target.text);
    result.put("desc", target.desc);
    result.put("viewId", target.viewId);
    result.put("bounds", boundsArray(target.bounds));
    if (command.optBoolean("click", false)) {
      JSONObject clicked = command.has("longClick") && command.optBoolean("longClick")
          ? click(jsonWithId(target.id), true)
          : click(jsonWithId(target.id), false);
      result.put("click", clicked);
      result.put("ok", clicked.optBoolean("ok", false));
    }
    return result;
  }

  private JSONObject jsonWithId(int id) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("id", id);
    return json;
  }

  private JSONObject press(JSONObject command) throws JSONException {
    String key = command.optString("key", "").toLowerCase(Locale.ROOT);
    switch (key) {
      case "back":
        return global("back");
      case "home":
        return global("home");
      case "recents":
        return global("recents");
      default:
        return errorJson("raw key events are sent with the dsh android_shell tool (su -c input keyevent)");
    }
  }

  private JSONObject resultJson(boolean ok, String message) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("ok", ok);
    result.put("dispatched", ok);
    result.put("message", message);
    return result;
  }

  // ---------------------------------------------------------------------------
  // Screenshot

  private JSONObject screenshot(int maxWidth, int quality, int displayId) throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return errorJson("screenshots require Android 11 or newer");
    }
    final CompletableFuture<AccessibilityService.ScreenshotResult> future = new CompletableFuture<>();
    takeScreenshot(displayId, getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
      @Override
      public void onSuccess(AccessibilityService.ScreenshotResult result) {
        future.complete(result);
      }

      @Override
      public void onFailure(int errorCode) {
        future.completeExceptionally(new RuntimeException("screenshot failed: " + errorCode));
      }
    });
    AccessibilityService.ScreenshotResult result = future.get(15, TimeUnit.SECONDS);
    HardwareBuffer buffer = result.getHardwareBuffer();
    Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
    if (bitmap == null) {
      buffer.close();
      return errorJson("could not wrap screenshot buffer");
    }
    Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    buffer.close();
    if (copy == null) return errorJson("could not copy screenshot");
    int width = copy.getWidth();
    int height = copy.getHeight();
    int targetWidth = maxWidth > 0 && width > maxWidth ? maxWidth : width;
    int targetHeight = Math.max(1, (int) ((long) height * targetWidth / width));
    Bitmap scaled = targetWidth == width ? copy
        : Bitmap.createScaledBitmap(copy, targetWidth, targetHeight, true);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output);
    byte[] bytes = output.toByteArray();
    if (scaled != copy) scaled.recycle();
    copy.recycle();
    JSONObject json = new JSONObject();
    json.put("ok", true);
    json.put("mimeType", "image/jpeg");
    json.put("width", targetWidth);
    json.put("height", targetHeight);
    json.put("screenWidth", width);
    json.put("screenHeight", height);
    json.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
    return json;
  }

  // ---------------------------------------------------------------------------
  // Shizuku

  private boolean shizukuRunning() {
    try {
      return Shizuku.pingBinder();
    } catch (Throwable ignored) {
      return false;
    }
  }

  private boolean shizukuGranted() {
    try {
      return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private JSONObject shizukuStatus() throws JSONException {
    JSONObject result = new JSONObject();
    result.put("ok", true);
    result.put("running", shizukuRunning());
    result.put("granted", shizukuGranted());
    try {
      result.put("uid", Shizuku.getUid());
    } catch (Throwable ignored) {
    }
    try {
      result.put("version", Shizuku.getVersion());
    } catch (Throwable ignored) {
    }
    return result;
  }

  private JSONObject shizukuAction(JSONObject command) throws JSONException {
    String op = command.optString("op", "status");
    if (op.equals("status")) return shizukuStatus();
    if (!op.equals("request")) return errorJson("unsupported shizuku op " + op);
    if (!shizukuRunning()) return errorJson("Shizuku service is not running");
    if (shizukuGranted()) return shizukuStatus();
    try {
      Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
      JSONObject result = shizukuStatus();
      result.put("requested", true);
      return result;
    } catch (Throwable error) {
      return errorJson("Shizuku permission request failed: " + error);
    }
  }

  private JSONObject runShizuku(String command) throws JSONException {
    if (command.trim().isEmpty()) return errorJson("missing command");
    if (!shizukuRunning()) return errorJson("Shizuku service is not running");
    if (!shizukuGranted()) return errorJson("Shizuku permission is not granted");
    try {
      Method newProcess = Shizuku.class.getDeclaredMethod(
          "newProcess", String[].class, String[].class, String.class);
      newProcess.setAccessible(true);
      Process process = (Process) newProcess.invoke(
          null, new String[] {"sh", "-c", command}, null, null);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread stdout = new Thread(() -> drain(process.getInputStream(), output));
      Thread stderr = new Thread(() -> drain(process.getErrorStream(), output));
      stdout.start();
      stderr.start();
      boolean finished = process instanceof ShizukuRemoteProcess
          ? ((ShizukuRemoteProcess) process).waitForTimeout(30, TimeUnit.SECONDS)
          : process.waitFor(30, TimeUnit.SECONDS);
      if (!finished) process.destroy();
      stdout.join(1000);
      stderr.join(1000);
      JSONObject result = new JSONObject();
      result.put("ok", true);
      result.put("code", finished ? process.exitValue() : -1);
      result.put("output", output.toString("UTF-8").trim());
      return result;
    } catch (Throwable error) {
      return errorJson("Shizuku command failed: " + error);
    }
  }

  private static void drain(InputStream input, OutputStream output) {
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = input.read(buffer)) != -1) {
        synchronized (output) {
          output.write(buffer, 0, read);
        }
      }
    } catch (IOException ignored) {
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  // ---------------------------------------------------------------------------
  // Virtual display

  private JSONObject virtualDisplayAction(JSONObject command) throws JSONException {
    String op = command.optString("op", "start");
    if (op.equals("start")) {
      int width = Math.max(320, Math.min(3840, command.optInt("width", 1280)));
      int height = Math.max(240, Math.min(3840, command.optInt("height", 720)));
      int dpi = Math.max(120, Math.min(640, command.optInt("dpi", 160)));
      String name = command.optString("name", "dsh-virtual");
      return startVirtualDisplay(name, width, height, dpi);
    }
    if (op.equals("resize")) {
      int width = Math.max(320, Math.min(3840, command.optInt("width", 1280)));
      int height = Math.max(240, Math.min(3840, command.optInt("height", 720)));
      int dpi = Math.max(120, Math.min(640, command.optInt("dpi", 160)));
      return resizeVirtualDisplay(width, height, dpi);
    }
    if (op.equals("stop")) {
      JSONObject result = new JSONObject();
      synchronized (virtualDisplayLock) {
        releaseVirtualDisplayLocked();
        result.put("ok", true);
        result.put("active", false);
      }
      return result;
    }
    if (op.equals("status")) return virtualDisplayStatus();
    return errorJson("unsupported virtual display op " + op);
  }

  private JSONObject startVirtualDisplay(String name, int width, int height, int dpi)
      throws JSONException {
    synchronized (virtualDisplayLock) {
      releaseVirtualDisplayLocked();
      ImageReader reader = null;
      try {
        reader = createVirtualDisplayReader(width, height);
        Surface surface = reader.getSurface();
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
            | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        VirtualDisplay display = displayManager().createVirtualDisplay(
            name, width, height, dpi, surface, flags);
        if (display == null) {
          reader.close();
          return errorJson("could not create virtual display");
        }
        virtualDisplay = display;
        virtualDisplayReader = reader;
        virtualDisplayName = name;
        Display displayInfo = display.getDisplay();
        virtualDisplayId = displayInfo == null ? -1 : displayInfo.getDisplayId();
        if (virtualDisplayId < 0) {
          releaseVirtualDisplayLocked();
          return errorJson("virtual display has no display id");
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("active", true);
        result.put("displayId", virtualDisplayId);
        result.put("name", name);
        result.put("width", width);
        result.put("height", height);
        result.put("densityDpi", dpi);
        return result;
      } catch (RuntimeException error) {
        if (reader != null) reader.close();
        return errorJson("virtual display failed: " + error);
      }
    }
  }

  private ImageReader createVirtualDisplayReader(int width, int height) {
    ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
    reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
      @Override
      public void onImageAvailable(ImageReader source) {
        Image image = null;
        try {
          image = source.acquireLatestImage();
        } catch (RuntimeException ignored) {
        } finally {
          if (image != null) image.close();
        }
      }
    }, new Handler(Looper.getMainLooper()));
    return reader;
  }

  private JSONObject resizeVirtualDisplay(int width, int height, int dpi) throws JSONException {
    synchronized (virtualDisplayLock) {
      if (virtualDisplay == null || virtualDisplayId < 0) {
        return errorJson("virtual display is not active");
      }
      ImageReader reader = null;
      try {
        reader = createVirtualDisplayReader(width, height);
        virtualDisplay.resize(width, height, dpi);
        virtualDisplay.setSurface(reader.getSurface());
        ImageReader old = virtualDisplayReader;
        virtualDisplayReader = reader;
        if (old != null) old.close();
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("active", true);
        result.put("displayId", virtualDisplayId);
        result.put("name", virtualDisplayName);
        result.put("width", width);
        result.put("height", height);
        result.put("densityDpi", dpi);
        return result;
      } catch (RuntimeException error) {
        if (reader != null) reader.close();
        return errorJson("virtual display resize failed: " + error);
      }
    }
  }

  private JSONObject virtualDisplayStatus() throws JSONException {
    JSONObject result = new JSONObject();
    synchronized (virtualDisplayLock) {
      boolean active = virtualDisplay != null && virtualDisplayId >= 0;
      result.put("ok", true);
      result.put("active", active);
      if (active) {
        result.put("displayId", virtualDisplayId);
        result.put("name", virtualDisplayName);
        Display display = virtualDisplay.getDisplay();
        if (display != null) {
          DisplayMetrics metrics = displayMetrics(display);
          result.put("width", metrics.widthPixels);
          result.put("height", metrics.heightPixels);
          result.put("densityDpi", metrics.densityDpi);
        }
      }
    }
    return result;
  }

  private void releaseVirtualDisplayLocked() {
    if (virtualDisplay != null) {
      virtualDisplay.release();
      virtualDisplay = null;
    }
    if (virtualDisplayReader != null) {
      virtualDisplayReader.close();
      virtualDisplayReader = null;
    }
    virtualDisplayId = -1;
    virtualDisplayName = "";
  }

  // ---------------------------------------------------------------------------
  // Display helpers

  private JSONObject displays() throws JSONException {
    DisplayManager manager = displayManager();
    JSONArray array = new JSONArray();
    for (Display display : manager.getDisplays()) {
      DisplayMetrics metrics = displayMetrics(display);
      int flags = display.getFlags();
      JSONObject item = new JSONObject();
      item.put("id", display.getDisplayId());
      item.put("name", display.getName());
      item.put("width", metrics.widthPixels);
      item.put("height", metrics.heightPixels);
      item.put("densityDpi", metrics.densityDpi);
      item.put("flags", flags);
      item.put("default", display.getDisplayId() == Display.DEFAULT_DISPLAY);
      item.put("presentation", (flags & Display.FLAG_PRESENTATION) != 0);
      item.put("private", (flags & Display.FLAG_PRIVATE) != 0);
      item.put("secure", (flags & Display.FLAG_SECURE) != 0);
      item.put("state", display.getState());
      item.put("rotation", display.getRotation());
      item.put("valid", display.isValid());
      array.put(item);
    }
    JSONObject result = new JSONObject();
    result.put("ok", true);
    result.put("displays", array);
    return result;
  }

  // ---------------------------------------------------------------------------
  // App and event helpers

  private JSONObject apps() throws JSONException {
    PackageManager manager = getPackageManager();
    JSONArray array = new JSONArray();
    List<ApplicationInfo> apps = manager.getInstalledApplications(PackageManager.GET_META_DATA);
    Collections.sort(apps, new Comparator<ApplicationInfo>() {
      @Override
      public int compare(ApplicationInfo left, ApplicationInfo right) {
        CharSequence a = manager.getApplicationLabel(left);
        CharSequence b = manager.getApplicationLabel(right);
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
      }
    });
    for (ApplicationInfo info : apps) {
      if (manager.getLaunchIntentForPackage(info.packageName) == null) continue;
      JSONObject item = new JSONObject();
      item.put("package", info.packageName);
      item.put("label", String.valueOf(manager.getApplicationLabel(info)));
      item.put("system", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
      array.put(item);
      if (array.length() >= 400) break;
    }
    JSONObject result = new JSONObject();
    result.put("ok", true);
    result.put("apps", array);
    return result;
  }

  private JSONObject eventList(long since) throws JSONException {
    JSONArray array = new JSONArray();
    synchronized (events) {
      for (JSONObject event : events) {
        if (event.optLong("seq") > since) array.put(event);
      }
    }
    JSONObject result = new JSONObject();
    result.put("ok", true);
    result.put("events", array);
    result.put("seq", eventSeq);
    return result;
  }

  private static int queryInt(Uri uri, String name, int fallback) {
    String raw = uri.getQueryParameter(name);
    if (raw == null) return fallback;
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String safe(CharSequence value) {
    return value == null ? "" : value.toString();
  }

  private static String join(List<CharSequence> values) {
    if (values == null || values.isEmpty()) return "";
    StringBuilder builder = new StringBuilder();
    for (CharSequence value : values) {
      if (builder.length() > 0) builder.append(' ');
      builder.append(value);
    }
    return builder.toString();
  }

  private static JSONArray boundsArray(Rect bounds) throws JSONException {
    JSONArray array = new JSONArray();
    array.put(bounds.left);
    array.put(bounds.top);
    array.put(bounds.right);
    array.put(bounds.bottom);
    return array;
  }

  private static String actionName(int id) {
    switch (id) {
      case AccessibilityNodeInfo.ACTION_FOCUS:
        return "focus";
      case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS:
        return "clear_focus";
      case AccessibilityNodeInfo.ACTION_SELECT:
        return "select";
      case AccessibilityNodeInfo.ACTION_CLEAR_SELECTION:
        return "clear_selection";
      case AccessibilityNodeInfo.ACTION_CLICK:
        return "click";
      case AccessibilityNodeInfo.ACTION_LONG_CLICK:
        return "long_click";
      case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
        return "accessibility_focus";
      case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
        return "clear_accessibility_focus";
      case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
        return "scroll_forward";
      case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
        return "scroll_backward";
      case AccessibilityNodeInfo.ACTION_CUT:
        return "cut";
      case AccessibilityNodeInfo.ACTION_COPY:
        return "copy";
      case AccessibilityNodeInfo.ACTION_PASTE:
        return "paste";
      case AccessibilityNodeInfo.ACTION_SET_TEXT:
        return "set_text";
      case AccessibilityNodeInfo.ACTION_EXPAND:
        return "expand";
      case AccessibilityNodeInfo.ACTION_COLLAPSE:
        return "collapse";
      case AccessibilityNodeInfo.ACTION_DISMISS:
        return "dismiss";
      default:
        return "action_" + id;
    }
  }

  private static final class Entry {
    final int id;
    final int parent;
    final int depth;
    final AccessibilityNodeInfo node;
    final Rect bounds = new Rect();
    final String className;
    final String text;
    final String desc;
    final String hint;
    final String paneTitle;
    final String viewId;
    final String packageName;
    final boolean clickable;
    final boolean longClickable;
    final boolean editable;
    final boolean scrollable;
    final boolean checkable;
    final boolean checked;
    final boolean enabled;
    final boolean focusable;
    final boolean focused;
    final boolean selected;
    final boolean visible;
    final List<String> actions;

    Entry(int id, int parent, int depth, AccessibilityNodeInfo node) {
      this.id = id;
      this.parent = parent;
      this.depth = depth;
      this.node = node;
      node.getBoundsInScreen(bounds);
      className = safe(node.getClassName());
      text = safe(node.getText());
      desc = safe(node.getContentDescription());
      hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? safe(node.getHintText()) : "";
      paneTitle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? safe(node.getPaneTitle()) : "";
      viewId = safe(node.getViewIdResourceName());
      packageName = safe(node.getPackageName());
      clickable = node.isClickable();
      longClickable = node.isLongClickable();
      editable = node.isEditable();
      scrollable = node.isScrollable();
      checkable = node.isCheckable();
      checked = node.isChecked();
      enabled = node.isEnabled();
      focusable = node.isFocusable();
      focused = node.isFocused();
      selected = node.isSelected();
      visible = node.isVisibleToUser();
      List<String> names = new ArrayList<>();
      for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
        CharSequence label = action.getLabel();
        names.add(label == null ? actionName(action.getId()) : label.toString());
      }
      actions = names;
    }

    JSONObject toJson() throws JSONException {
      JSONObject json = new JSONObject();
      json.put("id", id);
      json.put("parent", parent);
      json.put("depth", depth);
      json.put("className", className);
      json.put("text", text);
      json.put("desc", desc);
      json.put("hint", hint);
      json.put("paneTitle", paneTitle);
      json.put("viewId", viewId);
      json.put("package", packageName);
      json.put("bounds", boundsArray(bounds));
      json.put("clickable", clickable);
      json.put("longClickable", longClickable);
      json.put("editable", editable);
      json.put("scrollable", scrollable);
      json.put("checkable", checkable);
      json.put("checked", checked);
      json.put("enabled", enabled);
      json.put("focusable", focusable);
      json.put("focused", focused);
      json.put("selected", selected);
      json.put("visible", visible);
      JSONArray actionArray = new JSONArray();
      for (String action : actions) actionArray.put(action);
      json.put("actions", actionArray);
      return json;
    }

    String toText() {
      StringBuilder line = new StringBuilder();
      for (int i = 0; i < depth; i++) line.append("  ");
      line.append('[').append(id).append("] ");
      if (!className.isEmpty()) line.append(simpleClass(className)).append(' ');
      if (!text.isEmpty()) line.append('"').append(escape(text)).append("\" ");
      if (!desc.isEmpty() && !desc.equals(text)) line.append("desc=\"").append(escape(desc)).append("\" ");
      if (!hint.isEmpty()) line.append("hint=\"").append(escape(hint)).append("\" ");
      if (!paneTitle.isEmpty()) line.append("pane=\"").append(escape(paneTitle)).append("\" ");
      if (!viewId.isEmpty()) line.append("id=").append(viewId).append(' ');
      line.append("b=[").append(bounds.left).append(',').append(bounds.top).append(',')
          .append(bounds.right).append(',').append(bounds.bottom).append("] ");
      if (clickable) line.append("clickable ");
      if (longClickable) line.append("long ");
      if (editable) line.append("editable ");
      if (scrollable) line.append("scrollable ");
      if (checkable) line.append(checked ? "checked " : "unchecked ");
      if (focused) line.append("focused ");
      if (!enabled) line.append("disabled ");
      if (!visible) line.append("invisible ");
      if (!actions.isEmpty()) {
        line.append("actions=").append(actions.toString().replace(" ", ""));
      }
      return line.toString().trim();
    }

    private static String simpleClass(String name) {
      int dot = name.lastIndexOf('.');
      return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String escape(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"")
          .replace("\n", "\\n").replace("\r", "\\r");
    }
  }
}

