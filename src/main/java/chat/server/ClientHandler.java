package chat.server;

import static chat.protocole.Protocole.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Participant connecté par socket TCP.
 *
 * Chaque instance s'exécute dans son propre thread (fourni par le pool du
 * serveur) : elle lit les lignes du client en boucle, ce qui bloque
 * uniquement ce thread et jamais les autres clients.
 */
public class ClientHandler extends Participant implements Runnable {

    private final Socket socket;
    private final SalonDeChat salon;
    private final int numero;
    private Writer sortie;

    public ClientHandler(Socket socket, SalonDeChat salon, int numero) {
        super("tcp", socket.getRemoteSocketAddress().toString());
        this.socket = socket;
        this.salon = salon;
        this.numero = numero;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("client-tcp-" + numero);
        setThread(Thread.currentThread().getName());
        boolean departVolontaire = false;
        try {
            socket.setTcpNoDelay(true);
            // UTF-8 explicite : sans cela Windows utiliserait cp1252 et les
            // accents seraient abîmés entre deux machines différentes.
            BufferedReader entree = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            synchronized (this) {
                sortie = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
            }

            // 1. Phase pseudonyme : le client n'est pas encore dans le salon.
            while (true) {
                envoyer(PSEUDO);
                String proposition = entree.readLine();
                if (proposition == null) {
                    return;
                }
                if (estDepart(proposition.trim())) {
                    departVolontaire = true;
                    return;
                }
                String erreur = salon.reserver(proposition, this);
                if (erreur == null) {
                    break;
                }
                envoyer(ligne(ERREUR, erreur));
            }

            // 2. Le client devient actif et reçoit les diffusions.
            salon.rejoindre(this);

            // 3. Boucle de lecture des messages.
            String ligne;
            while ((ligne = entree.readLine()) != null) {
                if (!salon.traiter(this, ligne)) {
                    departVolontaire = true;
                    break;
                }
            }
        } catch (IOException e) {
            // Déconnexion brutale (terminal fermé, réseau coupé...) :
            // le nettoyage est fait dans finally.
        } finally {
            salon.quitter(this);
            if (departVolontaire) {
                envoyer(BYE);
            }
            fermer();
        }
    }

    /**
     * Synchronisé : plusieurs threads (diffusions venant d'autres clients)
     * peuvent écrire sur ce socket en même temps.
     */
    @Override
    public synchronized void envoyer(String ligne) {
        if (sortie == null || socket.isClosed()) {
            return;
        }
        try {
            // « \n » explicite : println utiliserait « \r\n » sous Windows.
            sortie.write(ligne);
            sortie.write('\n');
            sortie.flush();
        } catch (IOException e) {
            // Le thread de lecture détectera la coupure et fera le ménage.
        }
    }

    @Override
    public void fermer() {
        try {
            socket.close();
        } catch (IOException e) {
            // déjà fermé
        }
    }
}
