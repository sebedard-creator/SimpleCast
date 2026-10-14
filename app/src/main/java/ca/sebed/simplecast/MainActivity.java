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
    private int loadGeneration;

    private static final class Player {
        String id, frame, label;
        int width, height;
        boolean playing;
        JavaScriptReplyProxy reply;
    }

    private final RemoteMediaClient.Callback mediaCallback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() { updateMediaControls(); }
    };
    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<>() {
        @Override public void onSessionStarted(CastSession session, String id) { connected(session); }
        @Override public void onSessionResumed(CastSession session, boolean suspended) { connected(session); }
        @Override public void onSessionStartFailed(CastSession session, int error) { castFailed(error); }
        @Override public void onSessionResumeFailed(CastSession session, int error) { castFailed(error); }
        @Override public void onSessionEnded(CastSession session, int error) {
            pending = null; loadGeneration++; attachRemote(null); status.setText("Chromecast déconnecté");
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
            if (remote != null) { if (remote.isPlaying()) remote.pause(); else remote.play(); }
        });
        controls.addView(playPause, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button stop = button("Arrêter"); stop.setOnClickListener(v -> {
            pending = null; loadGeneration++;
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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        browser.setWebChromeClient(new WebChromeClient() {
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
                catalog.clear(); players.clear(); frames.clear(); pending = null;
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
                return !StreamCatalog.validUrl(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.equals(HOME_URL) || url.equals(DEMO_URL) || url.equals("https://simplecast.test/embedded-demo")) {
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(
                        asset(url.equals(HOME_URL) ? "welcome.html" : url.equals(DEMO_URL) ? "demo.html" : "embedded-demo.html").getBytes(StandardCharsets.UTF_8)));
                }
                if (StreamCatalog.mediaMime(url, "") != null) {
                    String generation = token;
                    String referer = request.getRequestHeaders().getOrDefault("Referer", "");
                    handler.post(() -> {
                        if (!generation.equals(token) || isFinishing() || isDestroyed()) return;
                        catalog.add(url, "", referer, "", "Flux réseau", null); updateDetection();
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
        if (remote == null || !remote.hasMediaSession())
            status.setText(players.size() + " lecteur(s) · " + catalog.size() + " flux détecté(s)");
    }
    private void scanAndShow() {
        frames.values().forEach(reply -> command(reply, "scan", ""));
        if (advancedDetection) browser.evaluateJavascript("window.__simpleCastControl && window.__simpleCastControl({action:'scan'})", null);
        else fallbackScan();
        handler.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) showPlayers(); }, 300);
    }
    private AlertDialog showPlayers() {
        List<Player> snapshot = new ArrayList<>(players.values());
        if (snapshot.isEmpty()) { showStreams(null); return null; }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < snapshot.size(); i++) {
            Player p = snapshot.get(i);
            labels.add((i + 1) + ". " + p.label + "\n" + host(p.frame)
                + (p.height > 0 ? " · " + p.width + " × " + p.height : "") + (p.playing ? " · en lecture" : ""));
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
        List<StreamCatalog.Stream> list = new ArrayList<>();
        for (StreamCatalog.Stream stream : catalog.all()) {
            if (player == null || stream.matches(player.frame, player.id)) list.add(stream);
        }
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Le flux n’est pas encore visible")
                .setMessage("Lance la vidéo dans la page, puis scanne à nouveau. Certains lecteurs ne chargent le flux qu’après un clic.\n\n"
                    + (!advancedDetection ? "Mets Android System WebView à jour pour détecter aussi les lecteurs dans les iframes.\n\n" : "")
                    + "Un lecteur DRM ou WebRTC peut ne pas fournir de flux utilisable par Chromecast.")
                .setPositiveButton("Retour à la page", null).setNeutralButton("Rechercher", (d, w) -> scanAndShow()).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (StreamCatalog.Stream stream : list) {
            labels.add(stream.format() + (Boolean.TRUE.equals(stream.live) ? " · en direct" : "")
                + "\n" + displayUrl(stream.url)
                + (player != null && !stream.exactMatch(player.frame, player.id) ? "\nFlux du même cadre · association possible" : ""));
        }
        new AlertDialog.Builder(this).setTitle(player == null ? "Choisis un flux vidéo" : player.label)
            .setItems(labels.toArray(new String[0]), (d, index) -> confirmStream(list.get(index), player))
            .setNegativeButton("Retour", (d, w) -> showPlayersIfAvailable()).show();
    }
    private void showPlayersIfAvailable() { if (!players.isEmpty()) showPlayers(); }
    private void confirmStream(StreamCatalog.Stream stream, Player player) {
        CheckBox live = new CheckBox(this); live.setText("Flux en direct");
        live.setChecked(Boolean.TRUE.equals(stream.live)); live.setPadding(dp(22), dp(10), dp(22), dp(10));
        new AlertDialog.Builder(this).setTitle("Envoyer à la télé")
            .setMessage(stream.format() + " · " + displayUrl(stream.url))
            .setView(live).setPositiveButton("Caster", (d, w) -> {
                if (castContext == null) { toast("Google Play Services est requis pour Chromecast."); return; }
                pending = stream; pendingLive = live.isChecked();
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
        attachRemote(session.getRemoteMediaClient());
        if (pending == null || remote == null) return;
        StreamCatalog.Stream stream = pending; pending = null;
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, stream.title.isEmpty() ? "SimpleCast" : stream.title);
        MediaInfo info = new MediaInfo.Builder(stream.url).setContentType(stream.mime)
            .setStreamType(pendingLive ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata).build();
        int generation = ++loadGeneration;
        status.setText("Chargement sur le Chromecast…");
        remote.load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .setCurrentTime(MediaLoadRequestData.PLAY_POSITION_UNASSIGNED).build()).setResultCallback(result -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                if (result.getStatus().isSuccess()) {
                    frames.values().forEach(reply -> command(reply, "pause", ""));
                    browser.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.pause())", null);
                    status.setText("Vidéo envoyée au Chromecast"); updateMediaControls();
                } else {
                    status.setText("Le Chromecast n’a pas pu lire ce flux");
                    new AlertDialog.Builder(this).setTitle("Lecture impossible")
                        .setMessage("Essaie un autre flux. Le serveur peut exiger des cookies, bloquer Chromecast, ou utiliser un format incompatible.\n\nCode Cast : " + result.getStatus().getStatusCode())
                        .setPositiveButton("OK", null).show();
                }
            });
    }
    private void attachRemote(RemoteMediaClient client) {
        if (remote != null) remote.unregisterCallback(mediaCallback);
        remote = client;
        if (remote != null) remote.registerCallback(mediaCallback);
        updateMediaControls();
    }
    private void updateMediaControls() {
        MediaStatus media = remote == null ? null : remote.getMediaStatus();
        boolean idle = media == null || media.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE;
        controls.setVisibility(remote != null && remote.hasMediaSession() && !idle ? View.VISIBLE : View.GONE);
        playPause.setText(remote != null && remote.isPlaying() ? "Pause" : "Lecture");
        if (media != null && idle && media.getIdleReason() == MediaStatus.IDLE_REASON_ERROR)
            status.setText("Échec de lecture sur Chromecast. Essaie un autre flux.");
    }
    private void castFailed(int error) {
        pending = null; status.setText("Connexion Chromecast impossible · " + error);
        toast("Vérifie que les deux appareils sont sur le même Wi-Fi.");
    }
    private void command(JavaScriptReplyProxy reply, String action, String id) {
        try { reply.postMessage(new JSONObject().put("action", action).put("player", id).toString()); }
        catch (Exception ignored) { }
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("SimpleCast · 0.1.1")
            .setMessage("1. Ouvre une page et lance sa vidéo.\n2. Scanne et choisis son lecteur.\n3. Choisis le flux, puis ton Chromecast.\n\n"
                + "MP4, WebM, HLS et DASH, selon les formats de ton Chromecast. Une adresse blob: n’est pas un lien vidéo : l’appli cherche le manifeste derrière.\n\n"
                + "Les lecteurs DRM, WebRTC, et les flux nécessitant des cookies ou en-têtes spéciaux ne sont pas pris en charge. Plusieurs lecteurs dans le même cadre peuvent rendre l’association des flux ambiguë.\n\n"
                + "Aucune pub ni télémétrie ajoutée par l’appli. Les pages visitées gardent leurs propres publicités. Google Play Services assure la connexion Cast.")
            .setPositiveButton("OK", null).setNeutralButton("Démonstration", (d, w) -> open(DEMO_URL))
            .setNegativeButton("Effacer les données Web", (d, w) -> {
                pending = null; browser.stopLoading();
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
    private String displayUrl(String value) { Uri url = Uri.parse(value); String path = url.getLastPathSegment(); return host(value) + " / " + truncate(path == null ? "vidéo" : path, 65); }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
}
