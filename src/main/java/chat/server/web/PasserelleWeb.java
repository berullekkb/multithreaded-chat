package chat.server.web;

import static chat.protocole.Protocole.*;

import chat.server.Participant;
import chat.server.SalonDeChat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Passerelle HTTP : sert l'interface web (dossier docs/) et permet à un
 * navigateur de participer au même salon que les clients TCP.
 *
 * Uniquement la bibliothèque standard (com.sun.net.httpserver) :
 *   GET  /api/status          état du serveur (JSON)
 *   POST /api/join            pseudo=...           -> {"ok":true,"jeton":"..."}
 *   GET  /api/flux?jeton=...  flux SSE : une ligne du protocole par événement
 *   POST /api/send            jeton=...&texte=...
 *   POST /api/leave           jeton=...
 *   GET  /*                   fichiers statiques de docs/
 *
 * Chaque flux SSE ouvert occupe un thread du pool « web-N » : comme pour
 * TCP, un navigateur connecté = un thread qui lui écrit.
 */
public class PasserelleWeb {

    private static final long DELAI_RECONNEXION_MS = 20_000;
    private static final long BATTEMENT_MS = 15_000;

    private final SalonDeChat salon;
    private final Path racine;
    private final int portTcp;
    private final HttpServer http;
    private final ExecutorService threads;
    private final ScheduledExecutorService nettoyeur;
    private final Map<String, ParticipantWeb> sessions = new ConcurrentHashMap<>();

    public PasserelleWeb(SalonDeChat salon, int port, Path racine, int portTcp) throws IOException {
        this.salon = salon;
        this.racine = racine.toAbsolutePath().normalize();
        this.portTcp = portTcp;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        AtomicInteger n = new AtomicInteger();
        this.threads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "web-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.nettoyeur = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "web-nettoyeur");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(threads);
        http.createContext("/api/status", this::status);
        http.createContext("/api/join", this::join);
        http.createContext("/api/flux", this::flux);
        http.createContext("/api/send", this::send);
        http.createContext("/api/leave", this::leave);
        http.createContext("/", this::fichier);
    }

    public void demarrer() {
        http.start();
        nettoyeur.scheduleAtFixedRate(this::nettoyer, 5, 5, TimeUnit.SECONDS);
    }

    public void arreter() {
        nettoyeur.shutdownNow();
        http.stop(0);
        threads.shutdownNow();
    }

    // ------------------------------------------------------------------ API

    private void status(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();
        long maintenant = System.currentTimeMillis();
        sb.append("{\"mode\":\"live\"")
          .append(",\"port_tcp\":").append(portTcp)
          .append(",\"port_web\":").append(http.getAddress().getPort())
          .append(",\"uptime_s\":").append((maintenant - salon.getDemarrage()) / 1000)
          .append(",\"messages\":").append(salon.getMessagesDiffuses())
          .append(",\"threads_actifs\":").append(Thread.activeCount())
          .append(",\"max\":").append(salon.getMaxParticipants())
          .append(",\"participants\":[");
        boolean premier = true;
        for (Participant p : salon.participantsActifs()) {
            if (!premier) {
                sb.append(',');
            }
            premier = false;
            sb.append("{\"pseudo\":").append(json(p.getPseudo()))
              .append(",\"type\":").append(json(p.getType()))
              .append(",\"thread\":").append(json(p.getThread()))
              .append(",\"connecte_s\":").append((maintenant - p.getDepuis()) / 1000)
              .append(",\"messages\":").append(p.getMessages())
              .append('}');
        }
        sb.append("]}");
        repondre(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    private void join(HttpExchange ex) throws IOException {
        if (!post(ex)) {
            return;
        }
        Map<String, String> f = formulaire(ex);
        String pseudo = f.getOrDefault("pseudo", "");
        ParticipantWeb p = new ParticipantWeb(UUID.randomUUID().toString(),
                ex.getRemoteAddress().toString());
        String erreur = salon.reserver(pseudo, p);
        if (erreur != null) {
            repondre(ex, 200, "application/json; charset=utf-8",
                    "{\"ok\":false,\"erreur\":" + json(erreur) + "}");
            return;
        }
        sessions.put(p.getJeton(), p);
        // Les premières lignes (OK, historique...) attendent dans la file
        // que le navigateur ouvre son flux.
        salon.rejoindre(p);
        repondre(ex, 200, "application/json; charset=utf-8",
                "{\"ok\":true,\"jeton\":" + json(p.getJeton())
                + ",\"pseudo\":" + json(p.getPseudo()) + "}");
    }

    private void flux(HttpExchange ex) throws IOException {
        ParticipantWeb p = sessions.get(requete(ex).getOrDefault("jeton", ""));
        if (p == null || p.estParti()) {
            repondre(ex, 404, "text/plain; charset=utf-8", "session inconnue");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        p.setThread(Thread.currentThread().getName());
        int gen = p.attacher();
        try (OutputStream out = ex.getResponseBody()) {
            out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (p.fluxCourant(gen)) {
                String ligne = p.prochaine(BATTEMENT_MS);
                if (!p.fluxCourant(gen)) {
                    if (ligne != null) {
                        p.remettre(ligne);
                    }
                    break;
                }
                if (ligne == null) {
                    out.write(": battement\n\n".getBytes(StandardCharsets.UTF_8));
                } else if (ligne.equals(ParticipantWeb.FIN)) {
                    out.write("event: fin\ndata: \n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    break;
                } else {
                    out.write(("data: " + ligne + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // onglet fermé ou réseau coupé : le nettoyeur attend une reconnexion
        } finally {
            p.detacher(gen);
            ex.close();
        }
    }

    private void send(HttpExchange ex) throws IOException {
        if (!post(ex)) {
            return;
        }
        Map<String, String> f = formulaire(ex);
        ParticipantWeb p = sessions.get(f.getOrDefault("jeton", ""));
        if (p == null || p.estParti()) {
            repondre(ex, 410, "text/plain; charset=utf-8", "session terminée");
            return;
        }
        if (!salon.traiter(p, f.getOrDefault("texte", ""))) {
            partir(p, true);
        }
        repondre(ex, 204, null, null);
    }

    private void leave(HttpExchange ex) throws IOException {
        if (!post(ex)) {
            return;
        }
        ParticipantWeb p = sessions.get(formulaire(ex).getOrDefault("jeton", ""));
        if (p != null) {
            partir(p, true);
        }
        repondre(ex, 204, null, null);
    }

    private void partir(ParticipantWeb p, boolean volontaire) {
        salon.quitter(p);
        if (volontaire) {
            p.envoyer(BYE);
        }
        p.fermer();
        sessions.remove(p.getJeton());
    }

    /** Retire les navigateurs disparus sans prévenir. */
    private void nettoyer() {
        long maintenant = System.currentTimeMillis();
        for (ParticipantWeb p : sessions.values()) {
            boolean perdu = !p.estAttache()
                    && maintenant - p.getDetacheDepuis() > DELAI_RECONNEXION_MS;
            if (p.estParti() || perdu || p.aDeborde()) {
                partir(p, false);
            }
        }
    }

    // ------------------------------------------------------ fichiers statiques

    private void fichier(HttpExchange ex) throws IOException {
        String methode = ex.getRequestMethod();
        if (!methode.equals("GET") && !methode.equals("HEAD")) {
            repondre(ex, 405, "text/plain; charset=utf-8", "méthode non autorisée");
            return;
        }
        String chemin = ex.getRequestURI().getPath();
        if (chemin.endsWith("/")) {
            chemin += "index.html";
        }
        Path cible = racine.resolve(chemin.substring(1)).normalize();
        // Empêche de sortir de docs/ avec des « ../ ».
        if (!cible.startsWith(racine) || !Files.isRegularFile(cible)) {
            repondre(ex, 404, "text/plain; charset=utf-8", "introuvable");
            return;
        }
        byte[] contenu = Files.readAllBytes(cible);
        ex.getResponseHeaders().set("Content-Type", typeMime(cible.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if (methode.equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, contenu.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(contenu);
        }
    }

    private static String typeMime(String nom) {
        String n = nom.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    // --------------------------------------------------------------- outils

    private static boolean post(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("POST")) {
            return true;
        }
        repondre(ex, 405, "text/plain; charset=utf-8", "POST attendu");
        return false;
    }

    private static void repondre(HttpExchange ex, int code, String type, String corps)
            throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (corps == null) {
            ex.sendResponseHeaders(code, -1);
            ex.close();
            return;
        }
        byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, octets.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(octets);
        }
    }

    private static Map<String, String> formulaire(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 64 * 1024) {
                buf.write(b, 0, n);
            }
            return decoder(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> requete(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return decoder(q == null ? "" : q);
    }

    private static Map<String, String> decoder(String donnees) {
        Map<String, String> m = new HashMap<>();
        for (String paire : donnees.split("&")) {
            if (paire.isEmpty()) {
                continue;
            }
            int i = paire.indexOf('=');
            String cle = i < 0 ? paire : paire.substring(0, i);
            String valeur = i < 0 ? "" : paire.substring(i + 1);
            try {
                m.put(URLDecoder.decode(cle, "UTF-8"), URLDecoder.decode(valeur, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                // paire mal encodée : ignorée
            }
        }
        return m;
    }

    static String json(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
