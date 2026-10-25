package ai.deepseek.dshbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DshWebActivity extends Activity {
  static final String EXTRA_URL = "url";

  private WebView webView;
  private String host;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().setDecorFitsSystemWindows(false);
    String tokenUrl = normalize(getIntent().getStringExtra(EXTRA_URL));
    Uri parsed = tokenUrl == null ? null : Uri.parse(tokenUrl);
    String scheme = parsed == null ? null : parsed.getScheme();
    if (parsed == null || parsed.getHost() == null
        || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
      Toast.makeText(this, "Invalid DSH Web UI URL", Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    host = parsed.getHost();
    boolean dark = (getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    int background = getColor(R.color.webview_background);

    webView = new WebView(this);
    webView.setBackgroundColor(background);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      settings.setAlgorithmicDarkeningAllowed(dark);
    } else {
      settings.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
    }
    webView.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (host.equals(uri.getHost())) return false;
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
      }
    });
    FrameLayout container = new FrameLayout(this);
    container.setBackgroundColor(background);
    container.addView(webView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    setContentView(container);
    applySystemBars(container);

    CookieManager cookies = CookieManager.getInstance();
    cookies.setAcceptCookie(true);
    cookies.setAcceptThirdPartyCookies(webView, true);
    final String pageUrl = stripToken(parsed);
    final String requestUrl = tokenUrl;
    new Thread(new Runnable() {
      @Override
      public void run() {
        List<String> values = fetchCookies(requestUrl);
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if (isFinishing() || isDestroyed()) return;
            for (String value : values) {
              CookieManager.getInstance().setCookie(pageUrl, value);
            }
            CookieManager.getInstance().flush();
            webView.loadUrl(values.isEmpty() ? requestUrl : pageUrl);
          }
        });
      }
    }, "dsh-web-cookie").start();
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

  private static List<String> fetchCookies(String url) {
    List<String> cookies = new ArrayList<>();
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.getResponseCode();
      for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
        if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
        for (String value : entry.getValue()) {
          if (value != null && !value.isEmpty()) cookies.add(value);
        }
      }
    } catch (IOException ignored) {
    } finally {
      if (connection != null) connection.disconnect();
    }
    return cookies;
  }

  private static String normalize(String url) {
    if (url == null) return null;
    Uri uri = Uri.parse(url);
    String host = uri.getHost();
    if (!"0.0.0.0".equals(host) && !"::".equals(host)) return url;
    Uri.Builder builder = uri.buildUpon();
    builder.authority(uri.getPort() >= 0 ? "127.0.0.1:" + uri.getPort() : "127.0.0.1");
    return builder.build().toString();
  }

  private static String stripToken(Uri uri) {
    return uri.buildUpon().clearQuery().fragment(null).build().toString();
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
    } else {
      super.onBackPressed();
    }
  }

  @Override
  protected void onDestroy() {
    if (webView != null) {
      webView.loadUrl("about:blank");
      webView.destroy();
      webView = null;
    }
    super.onDestroy();
  }
}
