package chat.server;

import static chat.protocole.Protocole.*;

import chat.server.web.PasserelleWeb;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Point d'entrée du serveur.
 *
 * Usage : java -cp bin chat.server.Serveur [--port 5000] [--web 8080]
 *                                          [--docs docs] [--max 50] [--sans-web]
 *
 * - port TCP pour les clients console (chat.client.Client) ;
 * - port HTTP pour l'interface web (dossier docs/ servi par le serveur).
 */
public class Serveur {

    private static final DateTimeFormatter HORODATAGE =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {
        int port = 5000;
        int portWeb = 8080;
        int max = 50;
        boolean web = true;
        Path docs = Paths.get("docs");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--web":
                    portWeb = Integer.parseInt(args[++i]);
                    break;
                case "--docs":
                    docs = Paths.get(args[++i]);
                    break;
                case "--max":
                    max = Integer.parseInt(args[++i]);
                    break;
                case "--sans-web":
                    web = false;
                    break;
                default:
                    System.err.println("Option inconnue : " + args[i]);
                    System.err.println("Usage : java -cp bin chat.server.Serveur "
                            + "[--port 5000] [--web 8080] [--docs docs] [--max 50] [--sans-web]");
                    System.exit(2);
            }
        }

        SalonDeChat salon = new SalonDeChat(max, Serveur::journal);

        // Un thread par client TCP, créé à la demande et réutilisé ensuite.
        // La file synchrone fait refuser le client au-delà de « max » threads.
        AtomicInteger compteurPool = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, max, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> new Thread(r, "pool-" + compteurPool.incrementAndGet()));

        System.out.println("=================================");
        System.out.println("     SERVEUR DE CHAT MULTI-THREAD");
        System.out.println("=================================");

        try (ServerSocket serveur = new ServerSocket(port)) {
            System.out.println("Clients console (TCP) : port " + port);

            PasserelleWeb passerelle = null;
            if (web) {
                passerelle = demarrerWeb(salon, portWeb, docs, port);
            }
            System.out.println("En attente de clients... (Ctrl+C pour arrêter)");

            final PasserelleWeb p = passerelle;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                journal("Arrêt du serveur...");
                salon.arreter();
                if (p != null) {
                    p.arreter();
                }
                pool.shutdownNow();
            }, "arret"));

            AtomicInteger numero = new AtomicInteger();
            while (true) {
                Socket socket = serveur.accept();
                journal("Nouvelle connexion : " + socket.getRemoteSocketAddress());
                ClientHandler client = new ClientHandler(socket, salon, numero.incrementAndGet());
                try {
                    pool.execute(client);
                } catch (RejectedExecutionException e) {
                    refuser(socket);
                }
            }
        } catch (IOException e) {
            System.out.println("Erreur du serveur : " + e.getMessage());
            System.exit(1);
        }
    }

    private static PasserelleWeb demarrerWeb(SalonDeChat salon, int portWeb, Path docs, int port) {
        if (!Files.isDirectory(docs)) {
            System.out.println("Interface web désactivée : dossier " + docs.toAbsolutePath()
                    + " introuvable (lancez le serveur depuis la racine du projet).");
            return null;
        }
        try {
            PasserelleWeb passerelle = new PasserelleWeb(salon, portWeb, docs, port);
            passerelle.demarrer();
            System.out.println("Interface web         : http://localhost:" + portWeb);
            for (String ip : adressesLocales()) {
                System.out.println("Sur le réseau local   : http://" + ip + ":" + portWeb);
            }
            return passerelle;
        } catch (IOException e) {
            System.out.println("Interface web désactivée : port " + portWeb + " indisponible ("
                    + e.getMessage() + "). Relancez avec --web 8081 par exemple.");
            return null;
        }
    }

    private static void refuser(Socket socket) {
        journal("Connexion refusée : serveur complet.");
        try (Socket s = socket; OutputStream out = s.getOutputStream()) {
            out.write((ligne(ERREUR, SalonDeChat.ERR_COMPLET) + "\n" + BYE + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // rien à faire
        }
    }

    static void journal(String message) {
        System.out.println("[" + LocalTime.now().format(HORODATAGE) + "] " + message);
    }

    private static List<String> adressesLocales() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.getAddress().length == 4 && a.isSiteLocalAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException e) {
            // pas d'adresse affichée
        }
        return ips;
    }
}
