package ca.sebed.simplecast;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends AppCompatActivity {
    private static final int BACKGROUND = Color.rgb(16, 34, 37);
    private static final int TEXT = Color.rgb(235, 245, 241);
    private static final int ACCENT = Color.rgb(84, 224, 192);
    private static final String HOME_URL = "https://simplecast.test/home";
    private static final String DEMO_URL = "https://simplecast.test/demo";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StreamCatalog catalog = new StreamCatalog();
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, JavaScriptReplyProxy> frames = new LinkedHashMap<>();
    private final java.util.Set<WebView> blockedPopups = new java.util.HashSet<>();
    private final Map<String, Map<String, String>> requestContexts = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) { return size() > 512; }
    };
    private volatile String token;
    private String detector;
    private String cleanup;
    private String compatibility;
    private volatile boolean filterEnabled;
    private boolean advancedDetection;
    private WebView browser;
    private EditText address;
    private TextView status;
    private Button sendButton, tvButton;
    private boolean scanning, nativeLoading, nativeError;
    private int scanGeneration;
    private String lastSentUrl;
    private LinearLayout controls;
    private Button playPause;
    private ProgressBar progress;
    private NativeTvClient nativeTv;
    private boolean nativePlayback, nativePaused;
    private int nativeGeneration;

    private static final class Player {
        String id, frame, label, source;
        int width, height;
        long area;
        boolean playing, visible;
        JavaScriptReplyProxy reply;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        detector = asset("detector.js");
        cleanup = asset("cleanup.js");
        compatibility = asset("browser-compat.js");
        BrowserPolicy.loadAdHosts(asset("ad-hosts.txt"));
        filterEnabled = getSharedPreferences("simplecast-browser", MODE_PRIVATE).getBoolean("ad-filter", true);
        createLayout();
        configureBrowser();
        nativeTv = new NativeTvClient(this);
        updateDetection();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (browser.canGoBack()) browser.goBack();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        String restored = state == null ? null : state.getString("page");
        if (!handleSharedIntent(getIntent())) open(restored == null ? HOME_URL : restored);
    }

    private void createLayout() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout header = row(); header.setPadding(dp(18), dp(8), dp(8), dp(4));
        TextView title = text("SimpleCast", 23); title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        tvButton = button("Associer une télé"); tvButton.setTextSize(12);
        tvButton.setMaxLines(1); tvButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvButton.setOnClickListener(v -> chooseNativeTv());
        header.addView(tvButton, new LinearLayout.LayoutParams(dp(148), dp(48)));
        Button menu = button("⋮"); menu.setTextSize(24); menu.setContentDescription("Options");
        menu.setOnClickListener(v -> showMenu(menu)); header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);
        LinearLayout urlRow = row(); urlRow.setPadding(dp(12), 0, dp(12), dp(4));
        address = new EditText(this); address.setSingleLine(); address.setTextColor(TEXT);
        address.setHintTextColor(Color.rgb(158, 183, 176)); address.setTextSize(15);
        address.setHint("Adresse du site de streaming");
        address.setContentDescription("Adresse du site");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { openInput(); return true; }
            return false;
        });
        urlRow.addView(address, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button go = button("Ouvrir"); go.setOnClickListener(v -> openInput());
        urlRow.addView(go); root.addView(urlRow);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        browser = new WebView(this); root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = column(); bottom.setPadding(dp(12), dp(6), dp(12), dp(8));
        status = text("Ouvre une page, puis lance sa vidéo.", 13); status.setPadding(dp(4), 0, 0, dp(4));
        bottom.addView(status);
        sendButton = button("Envoyer à la télé"); sendButton.setTextColor(BACKGROUND);
        sendButton.setTypeface(null, Typeface.BOLD);
        androidx.core.view.ViewCompat.setBackgroundTintList(sendButton, android.content.res.ColorStateList.valueOf(ACCENT));
        sendButton.setOnClickListener(v -> sendCurrentVideo());
        bottom.addView(sendButton, new LinearLayout.LayoutParams(-1, dp(54)));
        controls = row();
        playPause = button("Pause"); playPause.setOnClickListener(v -> nativeCommand(nativePaused ? "play" : "pause"));
        controls.addView(playPause, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button stop = button("Arrêter"); stop.setOnClickListener(v -> nativeCommand("stop"));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(46), 1));
        controls.setVisibility(View.GONE); bottom.addView(controls); root.addView(bottom);
        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureBrowser() {
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setSupportZoom(true); settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false); settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // Contain new windows separately so failed popups cannot replace the video page.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
                return containPopup(result);
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!filterEnabled) return false;
                result.cancel(); return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!filterEnabled) return false;
                result.cancel(); return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String value, android.webkit.JsPromptResult result) {
                if (!filterEnabled) return false;
                result.cancel(); return true;
            }
            @Override public boolean onJsBeforeUnload(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!filterEnabled) return false;
                result.confirm(); return true;
            }
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value); progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE);
            }
        });
        advancedDetection = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (advancedDetection) WebViewCompat.addWebMessageListener(browser, "SimpleCastDetector", Collections.singleton("*"),
            (view, message, origin, main, reply) -> receive(message.getData(), reply));
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                catalog.clear(); players.clear(); frames.clear(); requestContexts.clear();
                scanGeneration++; scanning = false; nativeError = false;
                address.setText(url.equals(HOME_URL) ? "" : url); updateDetection();
                // A direct media link may never create an HTML document.
                catalog.add(url, "", url, "", "Lien vidéo direct", null); updateDetection();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!advancedDetection) browser.evaluateJavascript(compatibility, null);
                if (!advancedDetection && filterEnabled) browser.evaluateJavascript(cleanup, null);
                if (advancedDetection) browser.evaluateJavascript("window.__simpleCastControl && window.__simpleCastControl({action:'scan'})", null);
                else {
                    // Old WebViews: inspect the top document; network interception still covers iframe requests.
                    fallbackScan();
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return BrowserPolicy.blockedNavigation(view.getUrl(), request.getUrl().toString(),
                    request.isForMainFrame(), request.hasGesture(), request.isRedirect(), filterEnabled);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (filterEnabled && BrowserPolicy.blockedResource(url))
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                if (url.equals(HOME_URL) || url.equals(DEMO_URL) || url.equals("https://simplecast.test/embedded-demo")) {
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(
                        asset(url.equals(HOME_URL) ? "welcome.html" : url.equals(DEMO_URL) ? "demo.html" : "embedded-demo.html").getBytes(StandardCharsets.UTF_8)));
                }
                Map<String, String> observed = new LinkedHashMap<>(request.getRequestHeaders());
                boolean media = StreamCatalog.mediaMime(url, "") != null;
                boolean hasOrigin = observed.keySet().stream().anyMatch(key -> key.equalsIgnoreCase("origin"));
                if (media || hasOrigin) {
                    String generation = token;
                    String referer = request.getRequestHeaders().getOrDefault("Referer", "");
                    handler.post(() -> {
                        if (!generation.equals(token) || isFinishing() || isDestroyed()) return;
                        requestContexts.put(url, observed);
                        if (media) { catalog.add(url, "", referer, "", "Flux réseau", null); updateDetection(); }
                    });
                }
                return null; // Observe requests without replacing responses or breaking the page.
            }
        });
        newDetector();
    }

    /** Keep popup probes functional without showing a window or loading its destination. */
    private boolean containPopup(android.os.Message result) {
        if (blockedPopups.size() >= 2 || !(result.obj instanceof WebView.WebViewTransport)) return false;
        WebView popup = new WebView(this);
        WebSettings settings = popup.getSettings();
        settings.setJavaScriptEnabled(false); settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true); settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        popup.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        popup.setWebChromeClient(new WebChromeClient() {
            @Override public void onCloseWindow(WebView view) { handler.post(() -> closePopup(view)); }
        });
        popup.setDownloadListener((url, agent, disposition, mime, length) -> { });
        blockedPopups.add(popup);
        ((WebView.WebViewTransport) result.obj).setWebView(popup); result.sendToTarget();
        handler.postDelayed(() -> closePopup(popup), 2000);
        return true;
    }
    private void closePopup(WebView popup) {
        if (!blockedPopups.remove(popup)) return;
        popup.stopLoading(); popup.destroy();
    }
    private androidx.webkit.ScriptHandler detectorHandle;
    private void newDetector() {
        token = UUID.randomUUID().toString();
        if (detectorHandle != null) detectorHandle.remove();
        if (advancedDetection) detectorHandle = WebViewCompat.addDocumentStartJavaScript(browser,
            compatibility + "\n" + (filterEnabled ? cleanup + "\n" : "") + detector.replace("__SCAN_TOKEN__", token), Collections.singleton("*"));
    }

    private void receive(String value, JavaScriptReplyProxy reply) {
        if (value == null || value.length() > 20000) return;
        try {
            JSONObject data = new JSONObject(value);
            if (!token.equals(data.optString("token"))) return;
            String frame = data.optString("frame");
            if (!StreamCatalog.validUrl(frame)) return;
            if (frames.size() < 100 || frames.containsKey(frame)) frames.put(frame, reply);
            String id = data.optString("player"); String key = frame + "\n" + id;
            switch (data.optString("event")) {
                case "player":
                    if (id.isEmpty() || id.length() > 100 || (players.size() >= 100 && !players.containsKey(key))) return;
                    Player player = players.getOrDefault(key, new Player());
                    player.id = id; player.frame = frame; player.reply = reply;
                    player.label = truncate(data.optString("label", "Lecteur vidéo"), 100);
                    player.width = Math.max(0, data.optInt("width")); player.height = Math.max(0, data.optInt("height"));
                    player.playing = data.optBoolean("playing"); player.visible = data.optBoolean("visible", true);
                    player.area = Math.max(0, data.optLong("area", (long) player.width * player.height));
                    player.source = data.optString("source"); players.put(key, player);
                    break;
                case "remove-player": players.remove(key); break;
                case "stream":
                    Boolean live = data.has("live") && !data.isNull("live") ? data.getBoolean("live") : null;
                    var detected = catalog.add(data.optString("url"), data.optString("mime"), frame, id,
                        truncate(data.optString("title"), 100), live);
                    if (detected != null) {
                        detected.fallback |= data.optBoolean("fallback");
                        detected.master |= data.optBoolean("master");
                    }
                    break;
                default: return;
            }
            updateDetection();
        } catch (Exception ignored) { }
    }

    private void fallbackScan() {
        String page = browser.getUrl();
        browser.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('video')).map((v,i)=>({id:'video-'+i,label:v.getAttribute('aria-label')||document.title,playing:!v.paused&&!v.ended,visible:v.getBoundingClientRect().width>0&&v.getBoundingClientRect().height>0,area:v.getBoundingClientRect().width*v.getBoundingClientRect().height,url:(v.getAttribute('src')||'').trim()?(v.currentSrc||v.src):(v.currentSrc!==location.href?v.currentSrc:''),sources:Array.from(v.querySelectorAll('source')).filter(s=>(s.getAttribute('src')||'').trim()).map(s=>({url:s.src,mime:s.type}))})))", result -> fallbackPlayers(result, page));
    }
    private void fallbackPlayers(String result, String page) {
        if (page == null || !page.equals(browser.getUrl()) || isDestroyed()) return;
        try {
            String json = (String) new org.json.JSONTokener(result).nextValue();
            var list = new org.json.JSONArray(json);
            for (int i = 0; i < Math.min(list.length(), 100); i++) {
                JSONObject data = list.getJSONObject(i);
                Player player = new Player(); player.id = data.getString("id"); player.frame = page;
                player.label = truncate(data.optString("label", "Lecteur vidéo"), 100);
                player.playing = data.optBoolean("playing"); player.visible = data.optBoolean("visible");
                player.area = Math.max(0, data.optLong("area")); player.source = data.optString("url");
                players.put(page + "\n" + player.id, player);
                catalog.add(data.optString("url"), "", page, player.id, player.label, null);
                var sources = data.getJSONArray("sources");
                for (int j = 0; j < sources.length(); j++) {
                    var source = sources.getJSONObject(j);
                    catalog.add(source.optString("url"), source.optString("mime"), page, player.id, player.label, null);
                }
            }
            updateDetection();
        } catch (Exception ignored) { }
    }

    private void openInput() {
        String url = address.getText().toString().trim();
        if (!url.contains("://")) url = "https://" + url;
        if (!StreamCatalog.validUrl(url)) { toast("Entre une adresse Web http:// ou https:// valide."); return; }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus(); open(url);
    }
    private void open(String url) {
        if (!StreamCatalog.validUrl(url)) return;
        catalog.clear(); players.clear(); frames.clear(); scanGeneration++; scanning = false; newDetector();
        browser.loadUrl(url);
    }

    private void updateDetection() {
        boolean paired = nativeTv != null && nativeTv.paired();
        tvButton.setText(paired ? "TV · " + nativeTv.name().replaceFirst("^SimpleCast TV · ", "") : "Associer une télé");
        tvButton.setContentDescription(paired ? "Télé associée : " + nativeTv.name() : "Associer une télé");
        sendButton.setEnabled(!scanning && !nativeLoading);
        sendButton.setAlpha(scanning || nativeLoading ? 0.6f : 1f);
        sendButton.setText(nativeLoading ? "Envoi en cours…" : scanning ? "Recherche de la vidéo…" : paired ? "Envoyer à la télé" : "Associer une télé");
        if (!nativePlayback && !nativeLoading && !scanning && !nativeError) {
            Player player = preferredPlayer();
            status.setText(player != null && player.playing && !streamsFor(player).isEmpty()
                ? "Vidéo prête à être envoyée" : "Lance la vidéo dans la page.");
        }
    }
    private Player preferredPlayer() {
        return players.values().stream().sorted(java.util.Comparator.<Player>comparingInt(p -> p.playing ? 0 : 1)
            .thenComparingInt(p -> p.visible ? 0 : 1)
            .thenComparing(java.util.Comparator.comparingLong((Player p) -> p.area).reversed()))
            .findFirst().orElse(null);
    }
    private List<StreamCatalog.Stream> streamsFor(Player player) {
        return catalog.forPlayback(player == null ? null : player.frame, player == null ? null : player.id,
            player == null ? null : player.source);
    }
    private void scanPage(Runnable complete) {
        if (scanning || nativeLoading) return;
        scanning = true; int generation = ++scanGeneration; updateDetection();
        // Replaced iframes must not leave a stale player selected for automatic sending.
        if (advancedDetection) players.clear();
        new ArrayList<>(frames.values()).forEach(reply -> command(reply, "scan", ""));
        if (advancedDetection) browser.evaluateJavascript("window.__simpleCastControl && window.__simpleCastControl({action:'scan'})", null);
        else fallbackScan();
        handler.postDelayed(() -> {
            if (generation != scanGeneration || isFinishing() || isDestroyed()) return;
            scanning = false; complete.run(); updateDetection();
        }, 700);
    }
    private void sendCurrentVideo() {
        if (scanning || nativeLoading) return;
        if (!nativeTv.paired()) { chooseNativeTv(); return; }
        scanPage(() -> {
            Player player = preferredPlayer();
            List<StreamCatalog.Stream> sources = streamsFor(player);
            if (sources.isEmpty()) showNoVideo();
            else if (player == null && sources.size() > 1) showSources();
            else sendNativeTv(sources.get(0));
        });
    }
    private void chooseSource() { scanPage(this::showSources); }
    private AlertDialog showSources() {
        Player selected = preferredPlayer();
        List<StreamCatalog.Stream> sources = streamsFor(selected);
        for (StreamCatalog.Stream stream : catalog.ranked(null, null)) if (!sources.contains(stream)) sources.add(stream);
        if (sources.isEmpty()) { showNoVideo(); return null; }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            StreamCatalog.Stream stream = sources.get(i);
            labels.add((i == 0 ? "Source recommandée" : "Autre source " + i) + " · " + stream.format()
                + (stream.fallback ? " · version alternative" : "")
                + (stream.url.equals(lastSentUrl) ? " · dernier essai" : "") + "\n" + host(stream.url));
        }
        return new AlertDialog.Builder(this).setTitle("Autre source vidéo")
            .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                if (!nativeTv.paired()) chooseNativeTv(); else sendNativeTv(sources.get(index));
            }).setNegativeButton("Annuler", null).show();
    }
    private void showNoVideo() {
        new AlertDialog.Builder(this).setTitle("Lance d’abord la vidéo")
            .setMessage("Appuie sur lecture dans le site, attends que la vidéo démarre, puis appuie sur Envoyer à la télé."
                + (!advancedDetection ? "\n\nMets Android System WebView à jour pour détecter les lecteurs intégrés." : ""))
            .setPositiveButton("Retour au site", null)
            .setNeutralButton("Autre source", (dialog, which) -> { if (catalog.size() > 0) showSources(); else toast("Aucune source détectée pour le moment."); }).show();
    }
    private void showMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Autre source vidéo");
        menu.getMenu().add(0, 2, 1, "Recharger la page");
        menu.getMenu().add(0, 3, 2, "Aide");
        menu.getMenu().add(0, 4, 3, "Filtre antipub").setCheckable(true).setChecked(filterEnabled);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) chooseSource();
            else if (item.getItemId() == 2) browser.reload();
            else if (item.getItemId() == 4) {
                setAdFilterEnabled(!filterEnabled);
            }
            else showHelp();
            return true;
        });
        menu.show();
    }
    private void setAdFilterEnabled(boolean enabled) {
        filterEnabled = enabled;
        getSharedPreferences("simplecast-browser", MODE_PRIVATE).edit().putBoolean("ad-filter", enabled).apply();
        newDetector(); browser.reload();
        toast(enabled ? "Filtre antipub activé" : "Filtre antipub désactivé");
    }
    private void chooseNativeTv() {
        if (nativeTv == null) return;
        if (nativeTv.paired()) {
            new AlertDialog.Builder(this).setTitle(nativeTv.name())
                .setMessage("Télé associée. Ouvre SimpleCast TV sur cette télé avant d’envoyer une vidéo.")
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Changer de télé", (d, w) -> discoverNativeTv()).show();
            return;
        }
        discoverNativeTv();
    }
    private void discoverNativeTv() {
        List<NativeTvClient.Target> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>(); labels.add("Saisir l’adresse affichée sur la télé…");
        var adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        AlertDialog chooser = new AlertDialog.Builder(this).setTitle("Ouvre SimpleCast TV sur la télé")
            .setAdapter(adapter, (dialog, index) -> {
                if (index == 0) manualNativeTv(); else pairNativeTv(targets.get(index - 1));
            }).setNegativeButton("Annuler", null).create();
        chooser.setOnDismissListener(d -> nativeTv.stopDiscovery()); chooser.show();
        nativeTv.discover(found -> {
            targets.clear(); targets.addAll(found); labels.clear(); labels.add("Saisir l’adresse affichée sur la télé…");
            found.forEach(target -> labels.add(target.name)); adapter.notifyDataSetChanged();
        });
    }
    private void manualNativeTv() {
        EditText input = new EditText(this); input.setSingleLine(); input.setHint("192.168.1.25:12345");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Adresse de SimpleCast TV").setView(input)
            .setPositiveButton("Continuer", (d, w) -> {
                try { pairNativeTv(NativeTvClient.manual(input.getText().toString())); }
                catch (Exception error) { toast("Entre l’adresse IP:port affichée dans SimpleCast TV."); }
            }).setNegativeButton("Annuler", null).show();
    }
    private void pairNativeTv(NativeTvClient.Target target) {
        EditText input = new EditText(this); input.setSingleLine(); input.setHint("Code à 6 chiffres");
        input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        new AlertDialog.Builder(this).setTitle(target.name).setMessage("Entre le code affiché dans SimpleCast TV.")
            .setView(input).setPositiveButton("Connecter", (d, w) -> {
                String pin = input.getText().toString();
                if (!pin.matches("[0-9]{6}")) { toast("Le code doit contenir 6 chiffres."); return; }
                status.setText("Connexion à SimpleCast TV…");
                nativeTv.pair(target, pin, new NativeTvClient.Result() {
                    @Override public void complete(JSONObject response) {
                        nativeGeneration++; nativePlayback = false; nativeLoading = false; nativeError = false;
                        updateMediaControls(); updateDetection(); status.setText("Télé associée · " + nativeTv.name());
                    }
                    @Override public void failed(String message) { status.setText(message); pairNativeTv(target); }
                });
            }).setNegativeButton("Annuler", null).show();
    }
    private void sendNativeTv(StreamCatalog.Stream stream) {
        if (!nativeTv.paired()) { chooseNativeTv(); return; }
        lastSentUrl = stream.url; nativeError = false; nativeLoading = true;
        nativePlayback = true; nativePaused = false; int generation = ++nativeGeneration;
        updateMediaControls(); updateDetection(); status.setText("Chargement sur " + nativeTv.name() + "…");
        try {
            Map<String, String> context = PlaybackContext.headers(stream.frame,
                requestContexts.getOrDefault(stream.url, Collections.emptyMap()), browser.getSettings().getUserAgentString());
            JSONObject body = new JSONObject().put("url", stream.url).put("mime", stream.mime).put("headers", new JSONObject(context));
            nativeTv.command("load", body, new NativeTvClient.Result() {
                @Override public void complete(JSONObject response) { pollNativeTv(generation, android.os.SystemClock.uptimeMillis() + 45000); }
                @Override public void failed(String message) { nativeFailure(message, generation); }
                @Override public void unavailable() { nativeUnavailable(generation); }
            });
        } catch (Exception error) { nativeFailure("Impossible de préparer ce flux.", generation); }
    }
    private void pollNativeTv(int generation, long deadline) {
        if (generation != nativeGeneration || !nativePlayback || isFinishing() || isDestroyed()) return;
        nativeTv.command("status", new JSONObject(), new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) {
                if (generation != nativeGeneration) return;
                String state = response.optString("state");
                if (state.equals("playing")) {
                    android.util.Log.i("SimpleCast", "Native TV playback confirmed; phone relay disabled");
                    frames.values().forEach(reply -> command(reply, "pause", ""));
                    browser.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.pause())", null);
                    nativeLoading = false; nativePaused = false; updateMediaControls(); updateDetection();
                    status.setText("Lecture directe sur SimpleCast TV");
                } else if (state.equals("error")) nativeFailure("La télé a refusé la lecture : " + response.optString("error")
                    + "\n\nLa télé reste associée. Tu peux essayer une autre source.", generation);
                else if (android.os.SystemClock.uptimeMillis() >= deadline) nativeFailure("SimpleCast TV n’a pas confirmé le démarrage de la vidéo après 45 secondes.", generation);
                else handler.postDelayed(() -> pollNativeTv(generation, deadline), 1000);
            }
            @Override public void failed(String message) { nativeFailure(message, generation); }
            @Override public void unavailable() { nativeUnavailable(generation); }
        });
    }
    private void nativeUnavailable(int generation) { nativeFailure(NativeTvClient.CONNECTION_HELP, generation, false); }
    private void nativeFailure(String message, int generation) {
        nativeFailure(message, generation, true);
    }
    private void nativeFailure(String message, int generation, boolean playbackError) {
        if (generation != nativeGeneration || isFinishing() || isDestroyed()) return;
        android.util.Log.w("SimpleCast", message);
        nativeGeneration++; nativePlayback = false; nativePaused = false; nativeLoading = false; nativeError = true; updateMediaControls(); updateDetection();
        status.setText(playbackError ? "Échec de lecture · appairage SimpleCast TV conservé" : "Télé indisponible · ouvre SimpleCast TV");
        var dialog = new AlertDialog.Builder(this).setTitle(playbackError ? "Lecture impossible" : "Télé indisponible")
            .setMessage(message).setNegativeButton("Fermer", null);
        if (playbackError) dialog.setPositiveButton("Autre source", (d, which) -> chooseSource());
        else dialog.setPositiveButton("Réessayer", (d, which) -> sendCurrentVideo());
        dialog.show();
    }
    private void nativeCommand(String action) {
        int generation = ++nativeGeneration;
        nativeTv.command(action, new JSONObject(), new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) {
                if (generation != nativeGeneration) return;
                nativeLoading = false;
                if (action.equals("stop")) { nativePlayback = false; status.setText("Lecture arrêtée sur SimpleCast TV"); }
                else { nativePaused = action.equals("pause"); status.setText(nativePaused ? "SimpleCast TV · en pause" : "Lecture sur SimpleCast TV"); }
                updateMediaControls(); updateDetection();
            }
            @Override public void failed(String message) { nativeFailure(message, generation); }
            @Override public void unavailable() { nativeUnavailable(generation); }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if (nativeTv == null || !nativeTv.paired() || nativeLoading) return;
        int generation = nativeGeneration;
        nativeTv.command("status", new JSONObject(), new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) {
                if (generation != nativeGeneration) return;
                String state = response.optString("state");
                nativePlayback = state.equals("playing") || state.equals("paused") || state.equals("loading");
                nativePaused = state.equals("paused"); nativeLoading = state.equals("loading");
                nativeError = state.equals("error"); updateMediaControls(); updateDetection();
                if (nativeLoading) { pollNativeTv(generation, android.os.SystemClock.uptimeMillis() + 45000); return; }
                if (nativePlayback) status.setText("SimpleCast TV · " + (nativePaused ? "en pause" : state.equals("loading") ? "chargement" : "lecture directe"));
                else if (state.equals("error")) status.setText("Flux indisponible · appairage SimpleCast TV conservé");
                else status.setText("Connecté à " + nativeTv.name());
            }
            @Override public void failed(String message) { if (generation == nativeGeneration) status.setText(message); }
            @Override public void unavailable() {
                if (generation != nativeGeneration) return;
                nativeLoading = false; nativePlayback = false; nativeError = true;
                updateMediaControls(); updateDetection(); status.setText("Télé indisponible · ouvre SimpleCast TV");
            }
        });
    }
    private void updateMediaControls() {
        controls.setVisibility(nativePlayback ? View.VISIBLE : View.GONE);
        playPause.setEnabled(!nativeLoading);
        playPause.setText(nativePaused ? "Lecture" : "Pause");
    }
    private void command(JavaScriptReplyProxy reply, String action, String id) {
        try { reply.postMessage(new JSONObject().put("action", action).put("player", id).toString()); }
        catch (Exception ignored) { }
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("SimpleCast · 0.4.4")
            .setMessage("1. Ouvre ton site de streaming.\n2. Lance la vidéo dans la page.\n3. Appuie sur Envoyer à la télé.\n\n"
                + "Associe ta télé une première fois avec le bouton en haut. SimpleCast TV doit être ouvert sur la télé, sur le même réseau Wi-Fi.\n\n"
                + "La télé télécharge la vidéo directement. Tu peux éteindre l’écran du téléphone après le démarrage.\n\n"
                + "Si tu n’as que le son ou si la lecture échoue, essaie Autre source vidéo dans le menu. L’association avec la télé est conservée.\n\n"
                + "Le filtre antipub réduit les bannières et les fenêtres parasites. Si un site fonctionne mal, désactive-le dans le menu. Certaines pubs intégrées à la vidéo peuvent rester.\n\n"
                + "Les vidéos protégées par DRM, WebRTC et certains codecs ne sont pas pris en charge.")
            .setPositiveButton("Fermer", null).setNeutralButton("Démonstration", (d, w) -> open(DEMO_URL)).show();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleSharedIntent(intent); }
    private boolean handleSharedIntent(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return false;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null) return false;
        Matcher matcher = Pattern.compile("https?://[^\\s<>\"\\p{Cntrl}]+", Pattern.CASE_INSENSITIVE).matcher(shared);
        if (!matcher.find() || !StreamCatalog.validUrl(matcher.group())) return false;
        open(matcher.group()); return true;
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("page", browser.getUrl()); }
    @Override protected void onDestroy() {
        if (nativeTv != null) nativeTv.close();
        handler.removeCallbacksAndMessages(null);
        new ArrayList<>(blockedPopups).forEach(this::closePopup);
        if (detectorHandle != null) detectorHandle.remove();
        browser.stopLoading(); browser.destroy(); super.onDestroy();
    }
    private String asset(String name) {
        try (var input = getAssets().open(name); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
        catch (Exception error) { throw new IllegalStateException("Ressource manquante : " + name, error); }
    }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String value, int size) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(TEXT); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private Button button(String value) { Button v = new androidx.appcompat.widget.AppCompatButton(this); v.setText(value); v.setAllCaps(false); v.setTextSize(14); v.setTextColor(TEXT); return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private String host(String value) { String host = Uri.parse(value).getHost(); return host == null ? "Page Web" : host; }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
}
