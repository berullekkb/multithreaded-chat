package chat.client;

import static chat.protocole.Protocole.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Client console.
 *
 * Usage : java -cp bin chat.client.Client [hôte] [port]
 * (par défaut localhost 5000).
 */
public class Client {

    public static void main(String[] args) {
        String hote = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(hote, port);
             Scanner clavier = new Scanner(System.in, encodageClavier())) {

            System.out.println("Connexion au serveur " + hote + ":" + port + " réussie.");

            BufferedReader entree = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter sortie = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8)), true);

            /*
             * Le pseudo est traité AVANT le lancement des threads :
             * un seul code lit le socket pendant la phase de connexion.
             */
            if (!choisirPseudo(entree, sortie, clavier)) {
                return;
            }

            MessageReceiver reception = new MessageReceiver(socket, entree);
            MessageSender envoi = new MessageSender(sortie, clavier);

            /*
             * Le thread d'envoi est un « démon » : il reste bloqué sur la
             * lecture du clavier, mais il n'empêchera pas le programme de
             * se terminer quand le serveur ferme la connexion.
             */
            envoi.setDaemon(true);

            reception.start();
            envoi.start();

            // Le programme se termine quand la réception s'arrête
            // (BYE après « exit », ou serveur arrêté).
            reception.join();

        } catch (IOException e) {
            System.out.println("Connexion au serveur impossible : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Encodage du terminal : sous Windows la console n'est pas en UTF-8
     * (cp850 en général). Le réseau, lui, est toujours en UTF-8.
     */
    private static String encodageClavier() {
        String nom = System.getProperty("stdout.encoding",
                System.getProperty("sun.stdout.encoding", Charset.defaultCharset().name()));
        return Charset.isSupported(nom) ? nom : Charset.defaultCharset().name();
    }

    /** @return false si la connexion se termine pendant le choix du pseudo. */
    private static boolean choisirPseudo(BufferedReader entree, PrintWriter sortie,
                                         Scanner clavier) throws IOException {
        while (true) {
            String ligne = entree.readLine();
            if (ligne == null) {
                System.out.println("Le serveur s'est déconnecté.");
                return false;
            }
            String type = type(ligne);

            if (type.equals(ERREUR)) {
                System.out.println("! " + decouper(ligne, 2)[1]);
                continue;
            }
            if (type.equals(BYE)) {
                return false;
            }
            if (type.equals(OK)) {
                System.out.println("Pseudo accepté. Vous pouvez discuter.");
                System.out.println("Tapez /help pour les commandes, exit pour quitter.");
                return true;
            }
            if (type.equals(PSEUDO)) {
                System.out.print("Entrez votre pseudo : ");
                try {
                    sortie.println(clavier.nextLine());
                } catch (NoSuchElementException e) {
                    // entrée standard fermée (Ctrl+Z / Ctrl+D)
                    return false;
                }
            }
        }
    }
}
