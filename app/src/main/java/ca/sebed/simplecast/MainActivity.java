package ca.sebed.simplecast;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.CheckBox;
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
import androidx.mediarouter.app.MediaRouteButton;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaError;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
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
    private final Map<String, Map<String, String>> requestContexts = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) { return size() > 512; }
    };
    private volatile String token;
    private String detector;
    private boolean advancedDetection;
    private WebView browser;
    private EditText address;
    private TextView status;
    private Button scanner;
    private LinearLayout controls;
    private Button playPause;
    private ProgressBar progress;
    private MediaRouteButton castButton;
    private CastContext castContext;
    private RemoteMediaClient remote;
    private StreamCatalog.Stream pending;
    private boolean pendingLive;
    private boolean pendingRelay;
    private int loadGeneration;
    private boolean loading;
    private boolean loadingWithRelay;
    private String loadingUrl;
    private Runnable loadWatchdog;
    private NativeTvClient nativeTv;
    private boolean nativePlayback, nativePaused;
    private int nativeGeneration;

    private static final class Player {
        String id, frame, label;
        int width, height;
        boolean playing;
        JavaScriptReplyProxy reply;
    }

    private final RemoteMediaClient.Callback mediaCallback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() { updateMediaControls(); observePlayback(); }
        @Override public void onMediaError(MediaError error) {
            android.util.Log.w("SimpleCast", "Cast media error" + mediaErrorDetails(error));
            if (loading) loadFailed("Le Chromecast a refusé la lecture du flux." + mediaErrorDetails(error), loadGeneration);
        }
    };
    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<>() {
        @Override public void onSessionStarted(CastSession session, String id) { connected(session); }
        @Override public void onSessionResumed(CastSession session, boolean suspended) { connected(session); }
        @Override public void onSessionStartFailed(CastSession session, int error) { castFailed(error); }
        @Override public void onSessionResumeFailed(CastSession session, int error) { castFailed(error); }
        @Override public void onSessionEnded(CastSession session, int error) {
            pending = null; cancelLoad(); RelayService.stop(MainActivity.this);
            attachRemote(null); if (!nativePlayback) status.setText("Chromecast déconnecté");
        }
        @Override public void onSessionSuspended(CastSession session, int reason) {
            attachRemote(null); status.setText("Connexion Chromecast interrompue");
        }
        @Override public void onSessionStarting(CastSession session) { status.setText("Connexion au Chromecast…"); }
        @Override public void onSessionEnding(CastSession session) { }
        @Override public void onSessionResuming(CastSession session, String id) { }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        detector = asset("detector.js");
        createLayout();
        configureBrowser();
        nativeTv = new NativeTvClient(this);
        try {
            castContext = CastContext.getSharedInstance(this);
            CastButtonFactory.setUpMediaRouteButton(this, castButton);
            castContext.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
            CastSession session = castContext.getSessionManager().getCurrentCastSession();
            if (session != null && session.isConnected()) attachRemote(session.getRemoteMediaClient());
        } catch (RuntimeException error) {
            castButton.setEnabled(false);
            status.setText("Google Play Services requis pour Chromecast. Détection disponible.");
        }
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
        castButton = new MediaRouteButton(this); castButton.setContentDescription("Choisir un Chromecast");
        castButton.setAlwaysVisible(true);
        header.addView(castButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button tv = button("TV"); tv.setContentDescription("Connexion SimpleCast TV");
        tv.setOnClickListener(v -> chooseNativeTv()); header.addView(tv, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button help = button("?"); help.setContentDescription("Aide et limites");
        help.setOnClickListener(v -> showHelp()); header.addView(help, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);
        LinearLayout urlRow = row(); urlRow.setPadding(dp(12), 0, dp(12), dp(4));
        address = new EditText(this); address.setSingleLine(); address.setTextColor(TEXT);
        address.setHintTextColor(Color.rgb(158, 183, 176)); address.setTextSize(15);
        address.setHint("Colle l’URL d’une page vidéo");
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
        LinearLayout actionRow = row();
        scanner = button("Scanner les lecteurs"); scanner.setTextColor(ACCENT);
        scanner.setOnClickListener(v -> scanAndShow());
        actionRow.addView(scanner, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button reload = button("↻"); reload.setContentDescription("Recharger la page");
        reload.setOnClickListener(v -> browser.reload());
        actionRow.addView(reload, new LinearLayout.LayoutParams(dp(52), dp(50))); bottom.addView(actionRow);
        controls = row();
        playPause = button("Pause"); playPause.setOnClickListener(v -> {
            if (nativePlayback) { nativeCommand(nativePaused ? "play" : "pause"); return; }
            if (remote != null) { if (remote.isPlaying()) remote.pause(); else remote.play(); }
        });
        controls.addView(playPause, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button stop = button("Arrêter"); stop.setOnClickListener(v -> {
            if (nativePlayback) { nativeCommand("stop"); return; }
            pending = null; cancelLoad(); RelayService.stop(this);
            if (remote != null) remote.stop();
        });
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(46), 1));
        controls.setVisibility(View.GONE); bottom.addView(controls); root.addView(bottom);
        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureBrowser() {
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // Without multiple-window handling, target=_blank can replace the video page.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
                return false; // Refuse both automatic popups and popunders triggered by a play click.
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
                catalog.clear(); players.clear(); frames.clear(); requestContexts.clear(); pending = null;
                address.setText(url.equals(HOME_URL) ? "" : url); updateDetection();
                // A direct media link may never create an HTML document.
                catalog.add(url, "", url, "", "Lien vidéo direct", null); updateDetection();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (advancedDetection) browser.evaluateJavascript("window.__simpleCastControl && window.__simpleCastControl({action:'scan'})", null);
                else {
                    // Old WebViews: inspect the top document; network interception still covers iframe requests.
                    fallbackScan();
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return BrowserPolicy.blockedNavigation(view.getUrl(), request.getUrl().toString(),
                    request.isForMainFrame(), request.hasGesture(), request.isRedirect());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (BrowserPolicy.blockedResource(url))
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

    private androidx.webkit.ScriptHandler detectorHandle;
    private void newDetector() {
        token = UUID.randomUUID().toString();
        if (detectorHandle != null) detectorHandle.remove();
        if (advancedDetection) detectorHandle = WebViewCompat.addDocumentStartJavaScript(browser,
            detector.replace("__SCAN_TOKEN__", token), Collections.singleton("*"));
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
                    player.playing = data.optBoolean("playing"); players.put(key, player);
                    break;
                case "remove-player": players.remove(key); break;
                case "stream":
                    Boolean live = data.has("live") && !data.isNull("live") ? data.getBoolean("live") : null;
                    catalog.add(data.optString("url"), data.optString("mime"), frame, id,
                        truncate(data.optString("title"), 100), live);
                    break;
                default: return;
            }
            updateDetection();
        } catch (Exception ignored) { }
    }

    private void fallbackScan() {
        String page = browser.getUrl();
        browser.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('video')).map((v,i)=>({id:'video-'+i,label:v.getAttribute('aria-label')||document.title,url:v.currentSrc||v.src,sources:Array.from(v.querySelectorAll('source')).map(s=>({url:s.src,mime:s.type}))})))", result -> fallbackPlayers(result, page));
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
        catalog.clear(); players.clear(); frames.clear(); pending = null; newDetector();
        browser.loadUrl(url);
    }

    private void updateDetection() {
        scanner.setText(players.isEmpty() ? "Scanner les lecteurs" : "Lecteurs détectés · " + players.size());
        if (!nativePlayback && !loading && (remote == null || !remote.hasMediaSession()))
            status.setText(players.size() + " lecteur(s) · " + catalog.size() + " flux détecté(s)");
    }
    private void scanAndShow() {
        // A changed iframe leaves its former player in native memory until the next scan.
        // Only live documents can answer this fresh request.
        if (advancedDetection) players.clear();
        frames.values().forEach(reply -> command(reply, "scan", ""));
        if (advancedDetection) browser.evaluateJavascript("window.__simpleCastControl && window.__simpleCastControl({action:'scan'})", null);
        else fallbackScan();
        handler.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) showPlayers(); }, 700);
    }
    private AlertDialog showPlayers() {
        List<Player> snapshot = new ArrayList<>(players.values());
        snapshot.sort(java.util.Comparator.<Player>comparingInt(p -> p.playing ? 0 : 1)
            .thenComparing(java.util.Comparator.comparingLong((Player p) -> (long) p.width * p.height).reversed()));
        if (snapshot.isEmpty()) { showStreams(null); return null; }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < snapshot.size(); i++) {
            Player p = snapshot.get(i);
            labels.add((i + 1) + ". " + (p.playing ? "▶ En lecture · " : "") + p.label + "\n" + host(p.frame)
                + (p.height > 0 ? " · " + p.width + " × " + p.height : ""));
        }
        labels.add("Tous les flux détectés (" + catalog.size() + ")");
        return new AlertDialog.Builder(this).setTitle("Choisis un lecteur")
            .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                if (index == snapshot.size()) { showStreams(null); return; }
                Player player = snapshot.get(index);
                if (player.reply != null) command(player.reply, "activate", player.id);
                handler.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) showStreams(player); }, 700);
            }).setNegativeButton("Fermer", null).show();
    }
    private void showStreams(Player player) {
        List<StreamCatalog.Stream> list = catalog.ranked(player == null ? null : player.frame, player == null ? null : player.id);
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Le flux n’est pas encore visible")
                .setMessage("Lance la vidéo dans la page, puis scanne à nouveau. Certains lecteurs ne chargent le flux qu’après un clic.\n\n"
                    + (!advancedDetection ? "Mets Android System WebView à jour pour détecter aussi les lecteurs dans les iframes.\n\n" : "")
                    + "Un lecteur DRM ou WebRTC peut ne pas fournir de flux utilisable par Chromecast.")
                .setPositiveButton("Retour à la page", null).setNeutralButton("Rechercher", (d, w) -> scanAndShow()).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        boolean exactSeen = false, possibleSeen = false;
        for (StreamCatalog.Stream stream : list) {
            boolean exact = player != null && stream.exactMatch(player.frame, player.id);
            boolean newest = exact ? !exactSeen : !possibleSeen;
            if (exact) exactSeen = true; else possibleSeen = true;
            labels.add(stream.format() + (Boolean.TRUE.equals(stream.live) ? " · en direct" : "") + (newest ? " · repéré le plus récemment" : "")
                + "\n" + displayUrl(stream.url)
                + (player == null ? "\nLecteur : " + host(stream.frame)
                    : exact ? "\nAssocié à ce lecteur" : "\nFlux du même cadre · association possible"));
        }
        new AlertDialog.Builder(this).setTitle(player == null ? "Choisis un flux vidéo" : player.label)
            .setItems(labels.toArray(new String[0]), (d, index) -> confirmStream(list.get(index), player))
            .setNegativeButton("Retour", (d, w) -> showPlayersIfAvailable()).show();
    }
    private void showPlayersIfAvailable() { if (!players.isEmpty()) showPlayers(); }
    private void confirmStream(StreamCatalog.Stream stream, Player player) {
        boolean useTv = nativeTv != null && nativeTv.paired();
        CheckBox live = new CheckBox(this); live.setText("Flux en direct");
        live.setChecked(Boolean.TRUE.equals(stream.live));
        live.setVisibility(useTv ? View.GONE : View.VISIBLE);
        CheckBox relay = new CheckBox(this); relay.setText("Relayer par le téléphone");
        relay.setChecked(false);
        relay.setVisibility(useTv || stream.format().equals("DASH") ? View.GONE : View.VISIBLE);
        LinearLayout options = column(); options.setPadding(dp(20), dp(6), dp(20), dp(10));
        options.addView(live); options.addView(relay);
        TextView relayHint = new TextView(this); relayHint.setText("En mode relais, garde ton téléphone connecté au Wi-Fi.");
        relayHint.setTextSize(13); relayHint.setPadding(dp(6), dp(4), dp(6), 0);
        relayHint.setVisibility(relay.isChecked() ? View.VISIBLE : View.GONE); options.addView(relayHint);
        if (useTv) {
            TextView direct = new TextView(this); direct.setText("La télé télécharge la vidéo. Tu peux éteindre l’écran du téléphone.");
            direct.setPadding(dp(6), dp(4), dp(6), 0); options.addView(direct);
        }
        relay.setOnCheckedChangeListener((button, checked) -> relayHint.setVisibility(checked ? View.VISIBLE : View.GONE));
        new AlertDialog.Builder(this).setTitle("Envoyer à la télé")
            .setMessage((useTv ? nativeTv.name() + "\n" : "") + stream.format() + " · " + displayUrl(stream.url))
            .setView(options).setPositiveButton(useTv ? "Lire sur SimpleCast TV" : "Caster", (d, w) -> {
                if (useTv) { sendNativeTv(stream); return; }
                nativePlayback = false; nativeGeneration++;
                if (castContext == null) { toast("Google Play Services est requis pour Chromecast."); return; }
                pending = stream; pendingLive = live.isChecked(); pendingRelay = relay.isChecked();
                CastSession session = castContext.getSessionManager().getCurrentCastSession();
                if (session != null && session.isConnected()) connected(session);
                else {
                    status.setText("Choisis ton Chromecast avec le bouton Cast en haut.");
                    castButton.performClick();
                }
            }).setNeutralButton("Copier l’URL", (d, w) -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Flux vidéo", stream.url));
                toast("URL du flux copiée");
            }).setNegativeButton("Annuler", null).show();
    }

    private void connected(CastSession session) {
        if (nativePlayback) return;
        attachRemote(session.getRemoteMediaClient());
        if (pending == null || remote == null) return;
        StreamCatalog.Stream stream = pending; pending = null;
        cancelLoad(); int generation = loadGeneration; boolean live = pendingLive;
        loading = true; loadingUrl = null; loadingWithRelay = pendingRelay;
        if (pendingRelay) {
            status.setText("Préparation du relais par le téléphone…");
            Map<String, String> headers = new LinkedHashMap<>(requestContexts.getOrDefault(stream.url, Collections.emptyMap()));
            if (headers.keySet().stream().noneMatch(key -> key.equalsIgnoreCase("user-agent")))
                headers.put("User-Agent", browser.getSettings().getUserAgentString());
            RelayService.prepare(this, stream.url, stream.frame, headers, stream.format().equals("HLS"), new RelayService.Ready() {
                @Override public void ready(String url) {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) { RelayService.stop(MainActivity.this, url); return; }
                    sendMedia(stream, url, live, generation);
                }
                @Override public void failed(String reason) { loadFailed(reason, generation); }
            });
        } else {
            RelayService.stop(this); sendMedia(stream, stream.url, live, generation);
        }
    }
    private void chooseNativeTv() {
        if (nativeTv == null) return;
        if (nativeTv.paired()) {
            new AlertDialog.Builder(this).setTitle("SimpleCast TV")
                .setMessage("Connecté à " + nativeTv.name() + ". La télé télécharge elle-même la vidéo.")
                .setPositiveButton("Fermer", null)
                .setNegativeButton("Déconnecter le téléphone", (d, w) -> {
                    nativeGeneration++; nativePlayback = false; nativeTv.disconnect(); updateMediaControls();
                    status.setText("SimpleCast TV déconnecté");
                }).show();
            return;
        }
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
                    @Override public void complete(JSONObject response) { status.setText("Connecté à " + nativeTv.name()); toast("Choisis maintenant ton lecteur et son flux."); }
                    @Override public void failed(String message) { status.setText(message); pairNativeTv(target); }
                });
            }).setNegativeButton("Annuler", null).show();
    }
    private void sendNativeTv(StreamCatalog.Stream stream) {
        pending = null; cancelLoad(); RelayService.stop(this); attachRemote(null);
        nativePlayback = true; nativePaused = false; int generation = ++nativeGeneration;
        updateMediaControls(); status.setText("Chargement sur SimpleCast TV…");
        try {
            Map<String, String> context = PlaybackContext.headers(stream.frame,
                requestContexts.getOrDefault(stream.url, Collections.emptyMap()), browser.getSettings().getUserAgentString());
            JSONObject body = new JSONObject().put("url", stream.url).put("mime", stream.mime).put("headers", new JSONObject(context));
            nativeTv.command("load", body, new NativeTvClient.Result() {
                @Override public void complete(JSONObject response) { pollNativeTv(generation, android.os.SystemClock.uptimeMillis() + 45000); }
                @Override public void failed(String message) { nativeFailure(message, generation); }
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
                    status.setText("Lecture directe sur SimpleCast TV");
                    nativePaused = false; updateMediaControls();
                } else if (state.equals("error")) nativeFailure("La télé a refusé la lecture : " + response.optString("error"), generation);
                else if (android.os.SystemClock.uptimeMillis() >= deadline) nativeFailure("SimpleCast TV n’a pas confirmé le démarrage de la vidéo après 45 secondes.", generation);
                else handler.postDelayed(() -> pollNativeTv(generation, deadline), 1000);
            }
            @Override public void failed(String message) { nativeFailure(message, generation); }
        });
    }
    private void nativeFailure(String message, int generation) {
        if (generation != nativeGeneration || isFinishing() || isDestroyed()) return;
        android.util.Log.w("SimpleCast", message);
        nativeGeneration++; status.setText("Échec de lecture sur SimpleCast TV");
        new AlertDialog.Builder(this).setTitle("SimpleCast TV").setMessage(message).setPositiveButton("OK", null).show();
    }
    private void nativeCommand(String action) {
        int generation = ++nativeGeneration;
        nativeTv.command(action, new JSONObject(), new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) {
                if (generation != nativeGeneration) return;
                if (action.equals("stop")) { nativePlayback = false; status.setText("Lecture arrêtée sur SimpleCast TV"); }
                else { nativePaused = action.equals("pause"); status.setText(nativePaused ? "SimpleCast TV · en pause" : "Lecture sur SimpleCast TV"); }
                updateMediaControls();
            }
            @Override public void failed(String message) { nativeFailure(message, generation); }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if (nativeTv == null || !nativeTv.paired()) return;
        int generation = nativeGeneration;
        nativeTv.command("status", new JSONObject(), new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) {
                if (generation != nativeGeneration) return;
                String state = response.optString("state");
                nativePlayback = state.equals("playing") || state.equals("paused") || state.equals("loading");
                nativePaused = state.equals("paused"); updateMediaControls();
                if (nativePlayback) status.setText("SimpleCast TV · " + (nativePaused ? "en pause" : state.equals("loading") ? "chargement" : "lecture directe"));
            }
            @Override public void failed(String message) { if (generation == nativeGeneration) status.setText(message); }
        });
    }
    private void sendMedia(StreamCatalog.Stream stream, String url, boolean live, int generation) {
        if (generation != loadGeneration) { RelayService.stop(this, url); return; }
        loadingUrl = url;
        android.util.Log.i("SimpleCast", "Cast load: host=" + host(url) + ", mime=" + stream.mime
            + ", live=" + live + ", relay=" + loadingWithRelay);
        if (remote == null) { loadFailed("La connexion au Chromecast a été interrompue.", generation); RelayService.stop(this, url); return; }
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, stream.title.isEmpty() ? "SimpleCast" : stream.title);
        MediaInfo info = new MediaInfo.Builder(url).setContentType(stream.mime)
            .setStreamType(live ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata).build();
        status.setText("Chargement sur le Chromecast…");
        loadWatchdog = () -> {
            if (loading && generation == loadGeneration) {
                loadFailed("Le Chromecast n’a pas démarré la vidéo après 45 secondes.", generation);
                if (remote != null) remote.stop();
            }
        };
        handler.postDelayed(loadWatchdog, 45000);
        remote.load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .setCurrentTime(MediaLoadRequestData.PLAY_POSITION_UNASSIGNED).build()).setResultCallback(result -> {
                if (generation != loadGeneration || !loading || isFinishing() || isDestroyed()) return;
                if (result.getStatus().isSuccess()) {
                    observePlayback(); // An accepted load isn't proof that the receiver can play the media.
                } else {
                    loadFailed("Le Chromecast n’a pas pu lire ce flux. Code Cast : " + result.getStatus().getStatusCode()
                        + mediaErrorDetails(result.getMediaError()), generation);
                }
            });
    }
    private void observePlayback() {
        if (!loading || remote == null || remote.getMediaInfo() == null || loadingUrl == null
            || !loadingUrl.equals(remote.getMediaInfo().getContentId())) return;
        if (remote.isPlaying()) {
            android.util.Log.i("SimpleCast", "Cast playback started: host=" + host(loadingUrl) + ", relay=" + loadingWithRelay);
            loading = false; if (loadWatchdog != null) handler.removeCallbacks(loadWatchdog);
            frames.values().forEach(reply -> command(reply, "pause", ""));
            browser.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.pause())", null);
            status.setText("Lecture sur le Chromecast" + (RelayService.isRelayUrl(loadingUrl) ? " · relais par le téléphone" : ""));
        } else if (remote.getMediaStatus() != null && remote.getMediaStatus().getPlayerState() == MediaStatus.PLAYER_STATE_IDLE
            && remote.getMediaStatus().getIdleReason() == MediaStatus.IDLE_REASON_ERROR) {
            // The detailed LOAD result can arrive just after the IDLE status.
            int generation = loadGeneration;
            handler.postDelayed(() -> {
                MediaStatus media = remote == null ? null : remote.getMediaStatus();
                if (media != null && media.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE
                    && media.getIdleReason() == MediaStatus.IDLE_REASON_ERROR)
                    loadFailed("Le Chromecast a rencontré une erreur de lecture.", generation);
            }, 500);
        }
    }
    private void loadFailed(String reason, int generation) {
        if (generation != loadGeneration || !loading || isFinishing() || isDestroyed()) return;
        android.util.Log.w("SimpleCast", reason);
        String relayError = loadingWithRelay ? RelayService.lastError() : "";
        String details = reason + (relayError.isEmpty() ? "" : "\n\n" + relayError);
        boolean relayed = loadingWithRelay;
        if (!relayed && loadingUrl != null) details += "\n\nSource : " + displayUrl(loadingUrl);
        cancelLoad(); if (relayed) RelayService.stop(this);
        status.setText("Le Chromecast n’a pas pu démarrer la vidéo");
        new AlertDialog.Builder(this).setTitle("Lecture impossible")
            .setMessage(details + "\n\n" + (relayed ? "Essaie un autre flux ou vérifie que le téléphone et la télé communiquent sur le même Wi-Fi."
                : "En lecture directe, le Chromecast doit pouvoir ouvrir le flux sans le lecteur du site. Le serveur ou son format de diffusion peut être incompatible. Essaie un autre flux."))
            .setPositiveButton("OK", null).show();
    }
    private static String mediaErrorDetails(MediaError error) {
        if (error == null) return "";
        StringBuilder details = new StringBuilder();
        if (error.getType() != null) details.append("\nType : ").append(error.getType());
        if (error.getReason() != null) details.append("\nRaison : ").append(error.getReason());
        if (error.getDetailedErrorCode() != null) details.append("\nCode du récepteur : ").append(error.getDetailedErrorCode());
        return details.toString();
    }
    private void cancelLoad() {
        loadGeneration++; loading = false;
        if (loadWatchdog != null) handler.removeCallbacks(loadWatchdog);
        loadWatchdog = null;
    }
    private void attachRemote(RemoteMediaClient client) {
        if (remote != null) remote.unregisterCallback(mediaCallback);
        remote = client;
        if (remote != null) remote.registerCallback(mediaCallback);
        updateMediaControls();
    }
    private void updateMediaControls() {
        if (nativePlayback) { controls.setVisibility(View.VISIBLE); playPause.setText(nativePaused ? "Lecture" : "Pause"); return; }
        MediaStatus media = remote == null ? null : remote.getMediaStatus();
        boolean idle = media == null || media.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE;
        controls.setVisibility(remote != null && remote.hasMediaSession() && !idle ? View.VISIBLE : View.GONE);
        playPause.setText(remote != null && remote.isPlaying() ? "Pause" : "Lecture");
        if (media != null && idle && media.getIdleReason() == MediaStatus.IDLE_REASON_ERROR)
            status.setText("Échec de lecture sur Chromecast. Essaie un autre flux.");
    }
    private void castFailed(int error) {
        pending = null; cancelLoad(); RelayService.stop(this); status.setText("Connexion Chromecast impossible · " + error);
        toast("Vérifie que les deux appareils sont sur le même Wi-Fi.");
    }
    private void command(JavaScriptReplyProxy reply, String action, String id) {
        try { reply.postMessage(new JSONObject().put("action", action).put("player", id).toString()); }
        catch (Exception ignored) { }
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("SimpleCast · 0.3.0")
            .setMessage("1. Ouvre une page et lance sa vidéo.\n2. Scanne et choisis son lecteur.\n3. Choisis le flux, puis ton Chromecast.\n\n"
                + "MP4, WebM, HLS et DASH, selon les formats de ton Chromecast. Une adresse blob: n’est pas un lien vidéo : l’appli cherche le manifeste derrière.\n\n"
                + "Par défaut, le Chromecast reçoit l’URL et télécharge lui-même la vidéo. Le téléphone sert de télécommande. Aucun relais n’est activé automatiquement.\n\n"
                + "Sur Android TV, installe et ouvre SimpleCast TV, puis utilise le bouton TV de cette appli pour les connecter. La télé lit directement la vidéo avec le contexte du site, sans relais par le téléphone.\n\n"
                + "L’option facultative « Relayer par le téléphone » fait passer la vidéo par le téléphone et consomme davantage de batterie.\n\n"
                + "Les lecteurs DRM, WebRTC et les codecs incompatibles ne sont pas pris en charge. Plusieurs lecteurs dans le même cadre peuvent rendre l’association des flux ambiguë.\n\n"
                + "Les nouvelles fenêtres et les changements automatiques vers un autre site sont bloqués, ainsi que quelques scripts publicitaires connus. Les liens qui ouvrent un nouvel onglet sont aussi bloqués : colle leur URL en haut au besoin. Ce filtre ne remplace pas un bloqueur complet comme uBlock Origin.\n\n"
                + "Aucune pub ni télémétrie ajoutée par l’appli. Google Play Services assure la connexion Cast.")
            .setPositiveButton("OK", null).setNeutralButton("Démonstration", (d, w) -> open(DEMO_URL))
            .setNegativeButton("Effacer les données Web", (d, w) -> {
                pending = null; cancelLoad(); RelayService.stop(this); browser.stopLoading();
                CookieManager.getInstance().removeAllCookies(done -> {
                    CookieManager.getInstance().flush(); android.webkit.WebStorage.getInstance().deleteAllData();
                    browser.clearCache(true); browser.clearHistory(); open(HOME_URL);
                });
            }).show();
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
        if (castContext != null) castContext.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
        if (remote != null) remote.unregisterCallback(mediaCallback);
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
    private String displayUrl(String value) { Uri url = Uri.parse(value); String path = url.getPath(); return host(value) + truncate(path == null || path.isEmpty() ? "/" : path, 140); }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
}
