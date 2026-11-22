package chat.server.web;

import chat.server.Participant;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Participant connecté depuis un navigateur.
 *
 * Les lignes du protocole sont mises dans une file ; le thread du flux
 * SSE (Server-Sent Events) de ce navigateur les vide et les écrit dans la
 * réponse HTTP. Diffuser vers un navigateur ne bloque donc jamais le salon.
 */
public class ParticipantWeb extends Participant {

    /** Ligne spéciale qui demande au thread du flux de s'arrêter. */
    static final String FIN = "\u0000FIN";

    private static final int CAPACITE = 1000;

    private final String jeton;
    private final LinkedBlockingDeque<String> file = new LinkedBlockingDeque<>(CAPACITE);
    private final AtomicInteger generation = new AtomicInteger();

    private volatile boolean attache;
    private volatile long detacheDepuis = System.currentTimeMillis();
    private volatile boolean deborde;

    public ParticipantWeb(String jeton, String adresse) {
        super("web", adresse);
        this.jeton = jeton;
    }

    @Override
    public void envoyer(String ligne) {
        if (!file.offer(ligne)) {
            // Navigateur qui ne lit plus : il sera retiré par le nettoyeur.
            deborde = true;
        }
    }

    @Override
    public void fermer() {
        if (!file.offer(FIN)) {
            file.clear();
            file.offer(FIN);
        }
    }

    /** Un nouveau flux remplace l'éventuel ancien (reconnexion d'EventSource). */
    int attacher() {
        attache = true;
        return generation.incrementAndGet();
    }

    void detacher(int gen) {
        if (generation.get() == gen) {
            attache = false;
            detacheDepuis = System.currentTimeMillis();
        }
    }

    boolean fluxCourant(int gen) {
        return generation.get() == gen;
    }

    String prochaine(long attenteMs) throws InterruptedException {
        return file.poll(attenteMs, TimeUnit.MILLISECONDS);
    }

    /** Remet une ligne en tête si le flux a été remplacé avant de l'écrire. */
    void remettre(String ligne) {
        file.offerFirst(ligne);
    }

    boolean estAttache() {
        return attache;
    }

    long getDetacheDepuis() {
        return detacheDepuis;
    }

    boolean aDeborde() {
        return deborde;
    }

    String getJeton() {
        return jeton;
    }
}
