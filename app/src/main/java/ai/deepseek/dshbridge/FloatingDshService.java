package ai.deepseek.dshbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FloatingDshService extends Service {
  private static final String CHANNEL_ID = "dsh-floating";
  private static final int NOTIFICATION_ID = 4102;
  private static final String ACTION_SHOW = "ai.deepseek.dshbridge.SHOW_FLOATING";
  private static final String ACTION_HIDE = "ai.deepseek.dshbridge.HIDE_FLOATING";
  private static final String EXTRA_URL = "url";
  private static volatile boolean showing;

  private WindowManager windowManager;
  private WindowManager.LayoutParams windowParams;
  private FrameLayout root;
  private LinearLayout panel;
  private LinearLayout header;
  private ImageView bubble;
  private TextView title;
  private TextView subtitle;
  private ImageView fullButton;
  private ImageView minimizeButton;
  private ImageView closeButton;
  private ImageView resizeHandle;
  private WebView webView;
  private String currentUrl = "";
  private boolean expanded;
  private boolean fullscreen;
  private int bubbleSize;
  private int panelWidth;
  private int panelHeight;
  private int imeBottom;
  private int bubbleX;
  private int bubbleY;
  private int panelX;
  private int panelY;
  private int touchSlop;

  static boolean isShowing() {
    return showing;
  }

  static void show(Context context, String url) {
    Intent intent = new Intent(context, FloatingDshService.class);
    intent.setAction(ACTION_SHOW);
    intent.putExtra(EXTRA_URL, url == null ? "" : url);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent);
    } else {
      context.startService(intent);
    }
  }

  static void hide(Context context) {
    showing = false;
    Intent intent = new Intent(context, FloatingDshService.class);
    intent.setAction(ACTION_HIDE);
    context.startService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
    createNotificationChannel();
    startForegroundCompat(buildNotification());
    buildOverlay();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
      hideOverlay();
      stopSelf();
      return START_NOT_STICKY;
    }
    String url = intent == null ? "" : intent.getStringExtra(EXTRA_URL);
    if (url == null) url = "";
    showOverlay(url);
    return START_NOT_STICKY;
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    applyWebTheme();
    updateBoundsForConfig();
  }

  @Override
  public void onDestroy() {
    hideOverlay();
    super.onDestroy();
  }

  private void applyWebTheme() {
    if (webView == null) return;
    boolean dark = (getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    WebSettings settings = webView.getSettings();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      settings.setAlgorithmicDarkeningAllowed(dark);
    } else {
      settings.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
    }
    webView.setBackgroundColor(getColor(R.color.webview_background));
    applyChromeTheme();
    String script = "(function(){var dark=" + dark + ";"
        + "document.documentElement.style.colorScheme=dark?'dark':'light';"
        + "if(document.body)document.body.toggleAttribute('data-ds-dark-theme',dark);})()";
    webView.evaluateJavascript(script, null);
  }

  private void applyChromeTheme() {
    if (panel != null) panel.setBackground(rounded(getColor(R.color.app_background), 18));
    if (header != null) header.setBackgroundColor(getColor(R.color.terminal_background));
    if (title != null) title.setTextColor(getColor(R.color.title_color));
    if (subtitle != null) subtitle.setTextColor(getColor(R.color.label_color));
    if (fullButton != null) fullButton.setColorFilter(getColor(R.color.label_color));
    if (minimizeButton != null) minimizeButton.setColorFilter(getColor(R.color.label_color));
    if (closeButton != null) closeButton.setColorFilter(getColor(R.color.label_color));
    if (resizeHandle != null) resizeHandle.setColorFilter(getColor(R.color.label_color));
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void buildOverlay() {
    DisplayMetrics metrics = getResources().getDisplayMetrics();
    int screenWidth = metrics.widthPixels;
    int screenHeight = metrics.heightPixels;
    bubbleSize = dp(58);
    panelWidth = Math.min(screenWidth - dp(24), dp(410));
    panelHeight = Math.min(screenHeight - dp(150), dp(650));
    bubbleX = Math.max(0, screenWidth - bubbleSize - dp(12));
    bubbleY = Math.max(dp(96), screenHeight / 3);
    panelX = Math.max(0, screenWidth - panelWidth - dp(12));
    panelY = statusBarHeight() + dp(8);

    root = new FrameLayout(this);
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override
      public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        int nextIme = insets.getInsets(WindowInsets.Type.ime()).bottom;
        if (nextIme > dp(40)) {
          imeBottom = nextIme;
          applyPanelHeight();
        } else if (nextIme == 0 && imeBottom > 0) {
          imeBottom = 0;
          applyPanelHeight();
        }
        return insets;
      }
    });
    panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackground(rounded(getColor(R.color.app_background), 18));
    panel.setElevation(dp(12));

    header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(14), dp(6), dp(8), dp(6));
    header.setBackgroundColor(getColor(R.color.terminal_background));

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setPadding(0, 0, dp(8), 0);
    labels.setOnTouchListener(new DragListener(false));
    title = new TextView(this);
    title.setText("DSH");
    title.setTextSize(16);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    title.setTextColor(getColor(R.color.title_color));
    subtitle = new TextView(this);
    subtitle.setText("floating conversation");
    subtitle.setTextSize(11);
    subtitle.setTextColor(getColor(R.color.label_color));
    labels.addView(title);
    labels.addView(subtitle);
    header.addView(labels, new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1));

    minimizeButton = iconButton(R.drawable.ic_minimize, "Minimize");
    minimizeButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        setExpanded(false);
      }
    });
    header.addView(minimizeButton);

    fullButton = iconButton(R.drawable.ic_maximize, "Maximize");
    fullButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        toggleFullscreen();
      }
    });
    header.addView(fullButton);

    closeButton = iconButton(R.drawable.ic_close, "Close");
    closeButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        hideOverlay();
        stopSelf();
      }
    });
    header.addView(closeButton);
    panel.addView(header, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

    webView = new WebView(this);
    webView.setBackgroundColor(getColor(R.color.webview_background));
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setSupportZoom(false);
    settings.setBuiltInZoomControls(false);
    applyWebTheme();
    CookieManager cookies = CookieManager.getInstance();
    cookies.setAcceptCookie(true);
    cookies.setAcceptThirdPartyCookies(webView, true);
    webView.setWebViewClient(new WebViewClient() {
      @Override
      public void onPageFinished(WebView view, String url) {
        applyWebTheme();
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (uri.getHost() != null && uri.getHost().equals(currentHost())) return false;
        try {
          Intent external = new Intent(Intent.ACTION_VIEW, uri);
          external.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(external);
        } catch (Exception ignored) {
        }
        return true;
      }
    });
    panel.addView(webView, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    root.addView(panel, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    bubble = new ImageView(this);
    bubble.setImageResource(R.drawable.deepseek_favicon);
    bubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
    bubble.setPadding(dp(9), dp(9), dp(9), dp(9));
    bubble.setBackground(rounded(Color.parseColor("#FAFAFC"), 29));
    bubble.setContentDescription("Open DSH floating conversation");
    bubble.setOnTouchListener(new DragListener(true));
    root.addView(bubble, new FrameLayout.LayoutParams(bubbleSize, bubbleSize));

    resizeHandle = new ImageView(this);
    resizeHandle.setImageResource(R.drawable.ic_resize);
    resizeHandle.setColorFilter(getColor(R.color.label_color));
    resizeHandle.setAlpha(0.55f);
    resizeHandle.setElevation(dp(20));
    resizeHandle.setContentDescription("Resize DSH window");
    resizeHandle.setOnTouchListener(new ResizeListener());
    FrameLayout.LayoutParams resizeParams = new FrameLayout.LayoutParams(
        dp(16), dp(16), Gravity.BOTTOM | Gravity.END);
    resizeParams.setMargins(0, 0, dp(2), dp(2));
    root.addView(resizeHandle, resizeParams);

    windowParams = new WindowManager.LayoutParams();
    windowParams.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        : WindowManager.LayoutParams.TYPE_PHONE;
    windowParams.format = PixelFormat.TRANSLUCENT;
    windowParams.gravity = Gravity.TOP | Gravity.START;
    windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    windowParams.width = bubbleSize;
    windowParams.height = bubbleSize;
    windowParams.x = bubbleX;
    windowParams.y = bubbleY;
    setExpanded(false, false);
  }

  private void showOverlay(String url) {
    if (root == null) buildOverlay();
    if (root.getParent() == null) {
      try {
        windowManager.addView(root, windowParams);
        showing = true;
        root.requestApplyInsets();
      } catch (Exception error) {
        subtitle.setText("Unable to show overlay: " + error.getMessage());
        showing = false;
        stopSelf();
        return;
      }
    }
    if (!url.isEmpty() && !url.equals(currentUrl)) {
      currentUrl = url;
      webView.loadUrl(url);
    } else if (currentUrl.isEmpty()) {
      subtitle.setText("Start DSH to load the conversation");
    }
  }

  private void hideOverlay() {
    showing = false;
    if (root != null && root.getParent() != null) {
      try {
        windowManager.removeView(root);
      } catch (Exception ignored) {
      }
    }
    if (webView != null) {
      webView.loadUrl("about:blank");
      webView.destroy();
      webView = null;
    }
    root = null;
    panel = null;
    bubble = null;
    resizeHandle = null;
  }

  private void setExpanded(boolean value) {
    setExpanded(value, true);
  }

  private void setExpanded(boolean value, boolean update) {
    expanded = value;
    if (panel != null) panel.setVisibility(value ? View.VISIBLE : View.GONE);
    if (bubble != null) bubble.setVisibility(value ? View.GONE : View.VISIBLE);
    if (resizeHandle != null) {
      resizeHandle.setVisibility(value && !fullscreen ? View.VISIBLE : View.GONE);
    }
    if (windowParams == null) return;
    if (value) {
      applyPanelBounds();
      windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    } else {
      fullscreen = false;
      if (fullButton != null) setFullButtonState(false);
      if (subtitle != null) subtitle.setText("floating conversation");
      windowParams.width = bubbleSize;
      windowParams.height = bubbleSize;
      windowParams.x = clamp(bubbleX, 0, screenWidth() - bubbleSize);
      windowParams.y = clamp(bubbleY, 0, screenHeight() - bubbleSize);
      windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    }
    if (update && root != null && root.getParent() != null) {
      windowManager.updateViewLayout(root, windowParams);
      root.requestApplyInsets();
    }
  }

  private void applyPanelHeight() {
    if (windowParams == null || !expanded || root == null) return;
    int oldWidth = windowParams.width;
    int oldHeight = windowParams.height;
    int oldX = windowParams.x;
    int oldY = windowParams.y;
    applyPanelBounds();
    if (oldWidth == windowParams.width && oldHeight == windowParams.height
        && oldX == windowParams.x && oldY == windowParams.y) return;
    if (root.getParent() != null) windowManager.updateViewLayout(root, windowParams);
  }

  private void applyPanelBounds() {
    if (windowParams == null) return;
    if (fullscreen) {
      windowParams.width = screenWidth();
      windowParams.height = Math.max(dp(280),
          screenHeight() - statusBarHeight() - Math.max(0, imeBottom - dp(4)));
      windowParams.x = 0;
      windowParams.y = statusBarHeight();
    } else {
      windowParams.width = panelWidth;
      windowParams.height = Math.max(dp(280), panelHeight - Math.max(0, imeBottom - dp(4)));
      windowParams.x = clamp(panelX, 0, screenWidth() - panelWidth);
      windowParams.y = clamp(panelY, 0, screenHeight() - windowParams.height);
    }
  }

  private void updateBoundsForConfig() {
    if (windowParams == null) return;
    int width = screenWidth();
    int height = screenHeight();
    int maxWidth = Math.max(dp(280), width - dp(24));
    int maxHeight = Math.max(dp(280), height - statusBarHeight() - dp(16));
    panelWidth = Math.min(Math.max(panelWidth, dp(280)), maxWidth);
    panelHeight = Math.min(Math.max(panelHeight, dp(280)), maxHeight);
    panelX = clamp(panelX, 0, Math.max(0, width - panelWidth));
    panelY = clamp(panelY, statusBarHeight() + dp(4),
        Math.max(statusBarHeight() + dp(4), height - panelHeight));
    bubbleX = clamp(bubbleX, 0, Math.max(0, width - bubbleSize));
    bubbleY = clamp(bubbleY, 0, Math.max(0, height - bubbleSize));
    if (expanded) {
      applyPanelBounds();
    } else {
      windowParams.width = bubbleSize;
      windowParams.height = bubbleSize;
      windowParams.x = bubbleX;
      windowParams.y = bubbleY;
    }
    if (root != null && root.getParent() != null) {
      windowManager.updateViewLayout(root, windowParams);
    }
  }

  private void toggleFullscreen() {
    if (!expanded) {
      setExpanded(true);
      return;
    }
    fullscreen = !fullscreen;
    setFullButtonState(fullscreen);
    if (resizeHandle != null) {
      resizeHandle.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
    }
    if (subtitle != null) {
      subtitle.setText(fullscreen ? "full conversation" : "floating conversation");
    }
    applyPanelHeight();
  }

  private String currentHost() {
    Uri uri = Uri.parse(currentUrl);
    return uri.getHost() == null ? "" : uri.getHost();
  }

  private void setFullButtonState(boolean full) {
    if (fullButton == null) return;
    fullButton.setImageResource(full ? R.drawable.ic_restore : R.drawable.ic_maximize);
    fullButton.setContentDescription(full ? "Restore" : "Maximize");
  }

  private ImageView iconButton(int drawable, String description) {
    ImageView button = new ImageView(this);
    button.setImageResource(drawable);
    button.setScaleType(ImageView.ScaleType.CENTER);
    button.setPadding(dp(10), dp(10), dp(10), dp(10));
    button.setColorFilter(getColor(R.color.label_color));
    button.setContentDescription(description);
    button.setClickable(true);
    button.setFocusable(true);
    button.setMinimumWidth(dp(44));
    button.setMinimumHeight(dp(44));
    return button;
  }

  private GradientDrawable rounded(int color, int radiusDp) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radiusDp));
    return drawable;
  }

  private int screenWidth() {
    return getResources().getDisplayMetrics().widthPixels;
  }

  private int screenHeight() {
    return getResources().getDisplayMetrics().heightPixels;
  }

  private int statusBarHeight() {
    int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
    return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(value, Math.max(min, max)));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (manager == null) return;
    NotificationChannel channel = new NotificationChannel(
        CHANNEL_ID, "DSH floating UI", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Keeps the DSH floating conversation available.");
    manager.createNotificationChannel(channel);
  }

  private Notification buildNotification() {
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent openPending = PendingIntent.getActivity(
        this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Intent hide = new Intent(this, FloatingDshService.class).setAction(ACTION_HIDE);
    PendingIntent hidePending = PendingIntent.getService(
        this, 2, hide, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ? new Notification.Builder(this, CHANNEL_ID)
        : new Notification.Builder(this);
    return builder
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("DSH floating UI")
        .setContentText("Conversation and approvals while DSH controls the phone")
        .setContentIntent(openPending)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher),
            "Hide", hidePending).build())
        .build();
  }

  private void startForegroundCompat(Notification notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }
  }

  private final class ResizeListener implements View.OnTouchListener {
    private float downX;
    private float downY;
    private int startWidth;
    private int startHeight;

    @Override
    public boolean onTouch(View view, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getRawX();
          downY = event.getRawY();
          startWidth = windowParams.width;
          startHeight = windowParams.height;
          return true;
        case MotionEvent.ACTION_MOVE:
          int width = clamp(startWidth + Math.round(event.getRawX() - downX),
              dp(280), screenWidth() - windowParams.x);
          int height = clamp(startHeight + Math.round(event.getRawY() - downY),
              dp(280), screenHeight() - windowParams.y);
          if (width == windowParams.width && height == windowParams.height) return true;
          windowParams.width = width;
          windowParams.height = height;
          panelWidth = width;
          panelHeight = height;
          if (root != null && root.getParent() != null) {
            windowManager.updateViewLayout(root, windowParams);
          }
          return true;
        default:
          return true;
      }
    }
  }

  private final class DragListener implements View.OnTouchListener {
    private final boolean tapToExpand;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private boolean moved;

    DragListener(boolean tapToExpand) {
      this.tapToExpand = tapToExpand;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = event.getRawX();
          downY = event.getRawY();
          startX = windowParams.x;
          startY = windowParams.y;
          moved = false;
          return true;
        case MotionEvent.ACTION_MOVE:
          float dx = event.getRawX() - downX;
          float dy = event.getRawY() - downY;
          if (!moved && Math.hypot(dx, dy) > touchSlop) moved = true;
          if (moved) {
            int x = clamp(startX + Math.round(dx), 0, screenWidth() - windowParams.width);
            int y = clamp(startY + Math.round(dy), 0, screenHeight() - windowParams.height);
            windowParams.x = x;
            windowParams.y = y;
            if (expanded) panelX = x; else bubbleX = x;
            if (expanded) panelY = y; else bubbleY = y;
            windowManager.updateViewLayout(root, windowParams);
          }
          return true;
        case MotionEvent.ACTION_UP:
          if (!moved && tapToExpand && !expanded) setExpanded(true);
          return true;
        default:
          return false;
      }
    }
  }
}
