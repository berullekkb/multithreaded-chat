package chat.client;

import static chat.protocole.Protocole.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;

/**
 * Thread qui écoute le serveur en continu et affiche les messages.
 */
public class MessageReceiver extends Thread {

    private final Socket socket;
    private final BufferedReader entree;

    public MessageReceiver(Socket socket, BufferedReader entree) {
        super("reception");
        this.socket = socket;
        this.entree = entree;
    }

    @Override
    public void run() {
        try {
            String ligne;
            while ((ligne = entree.readLine()) != null) {
                if (ligne.equals(BYE)) {
                    System.out.println("Déconnecté. À bientôt !");
                    return;
                }
                String texte = formater(ligne);
                if (texte != null) {
                    System.out.println(texte);
                }
            }
            System.out.println("Le serveur s'est déconnecté.");
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.out.println("Connexion avec le serveur perdue.");
            }
        }
    }

    /** Transforme une ligne du protocole en texte lisible (null = rien à afficher). */
    static String formater(String ligne) {
        String type = type(ligne);
        String[] c;
        switch (type) {
            case MSG:
                c = decouper(ligne, 4);
                return "[" + c[1] + "] " + c[2] + " : " + c[3];
            case HISTO:
                c = decouper(ligne, 4);
                return "[" + c[1] + "] (historique) " + c[2] + " : " + c[3];
            case PRIVE:
                c = decouper(ligne, 5);
                return "[" + c[1] + "] (privé) " + c[2] + " -> " + c[3] + " : " + c[4];
            case INFO:
                c = decouper(ligne, 3);
                return "[" + c[1] + "] * " + c[2];
            case ERREUR:
                return "! " + decouper(ligne, 2)[1];
            case USERS:
                return null; // utilisé par l'interface web ; /list pour la console
            default:
                return ligne;
        }
    }
}
