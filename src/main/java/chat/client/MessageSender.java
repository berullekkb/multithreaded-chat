package chat.client;

import static chat.protocole.Protocole.estDepart;

import java.io.PrintWriter;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Thread qui lit le clavier et envoie chaque ligne au serveur.
 */
public class MessageSender extends Thread {

    private final PrintWriter sortie;
    private final Scanner clavier;

    public MessageSender(PrintWriter sortie, Scanner clavier) {
        super("envoi");
        this.sortie = sortie;
        this.clavier = clavier;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String message = clavier.nextLine();
                sortie.println(message);
                if (estDepart(message.trim())) {
                    break;
                }
            }
        } catch (NoSuchElementException | IllegalStateException e) {
            // Entrée standard fermée : on prévient le serveur.
            sortie.println("exit");
        }
    }
}
