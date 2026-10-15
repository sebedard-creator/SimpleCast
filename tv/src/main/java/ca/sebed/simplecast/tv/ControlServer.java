package ca.sebed.simplecast.tv;

import ca.sebed.simplecast.PlaybackContext;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/** Local control channel only: never serves or relays video bytes. */
final class ControlServer extends NanoHTTPD {
    interface Listener { void command(String command, JSONObject body); }
    private final Listener listener;
    private final String pin = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    private String token = UUID.randomUUID().toString();
    private int failures;
    private long window;
    volatile String state = "idle", error = "";

    ControlServer(Listener listener) { super("0.0.0.0", 0); this.listener = listener; }
    String pin() { return pin; }
    @Override public synchronized Response serve(IHTTPSession session) {
        try {
            if (Method.POST != session.getMethod()) return reply(Response.Status.METHOD_NOT_ALLOWED, "Méthode refusée");
            int size = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
            if (size < 2 || size > 32768 || session.getHeaders().containsKey("transfer-encoding"))
                return reply(Response.Status.BAD_REQUEST, "Requête invalide");
            byte[] bytes = new byte[size]; int count = 0;
            while (count < size) {
                int read = session.getInputStream().read(bytes, count, size - count);
                if (read < 0) return reply(Response.Status.BAD_REQUEST, "Requête incomplète");
                count += read;
            }
            JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (session.getUri().equals("/pair")) {
                long now = System.nanoTime();
                if (now - window > 60000000000L) { window = now; failures = 0; }
                if (failures >= 5) return reply(Response.Status.FORBIDDEN, "Réessaie dans une minute");
                if (!pin.equals(body.optString("pin"))) { failures++; return reply(Response.Status.FORBIDDEN, "Code incorrect"); }
                failures = 0; token = UUID.randomUUID().toString();
                return json(new JSONObject().put("token", token));
            }
            String supplied = session.getHeaders().getOrDefault("x-simplecast-token", "");
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
                return reply(Response.Status.FORBIDDEN, "Connexion SimpleCast TV requise");
            String command = session.getUri().substring(1);
            if (command.equals("status")) return json(new JSONObject().put("state", state).put("error", error));
            if (!java.util.Arrays.asList("load", "play", "pause", "stop").contains(command))
                return reply(Response.Status.NOT_FOUND, "Commande inconnue");
            if (command.equals("load") && !PlaybackContext.validUrl(body.optString("url")))
                return reply(Response.Status.BAD_REQUEST, "Adresse vidéo invalide");
            if (command.equals("load")) { state = "loading"; error = ""; }
            listener.command(command, body);
            return json(new JSONObject().put("accepted", true));
        } catch (Exception error) { return reply(Response.Status.BAD_REQUEST, "Requête invalide"); }
    }
    private Response reply(Response.Status status, String message) {
        try { return newFixedLengthResponse(status, "application/json", new JSONObject().put("error", message).toString()); }
        catch (Exception ignored) { return newFixedLengthResponse(status, "text/plain", message); }
    }
    private Response json(JSONObject value) { return newFixedLengthResponse(Response.Status.OK, "application/json", value.toString()); }
}
