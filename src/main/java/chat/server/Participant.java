package chat.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Un utilisateur du salon, quel que soit son moyen de connexion :
 * socket TCP ({@link ClientHandler}) ou navigateur web
 * ({@link chat.server.web.ParticipantWeb}).
 *
 * Le salon ne connaît que cette classe : il n'a pas besoin de savoir
 * comment les lignes sont réellement transmises.
 */
public abstract class Participant {

    private final String type;
    private final String adresse;
    private final long depuis = System.currentTimeMillis();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicBoolean parti = new AtomicBoolean(false);

    private volatile String pseudo;
    private volatile boolean actif;
    private volatile String thread = "-";

    protected Participant(String type, String adresse) {
        this.type = type;
        this.adresse = adresse;
    }

    /** Transmet une ligne du protocole à ce participant. */
    public abstract void envoyer(String ligne);

    /** Ferme la connexion sous-jacente (socket ou flux web). */
    public abstract void fermer();

    public String getPseudo() {
        return pseudo;
    }

    void setPseudo(String pseudo) {
        this.pseudo = pseudo;
    }

    /** Actif = pseudo accepté et historique reçu : il reçoit les diffusions. */
    public boolean estActif() {
        return actif;
    }

    void activer() {
        actif = true;
    }

    /** Renvoie true une seule fois : sert à rendre le départ idempotent. */
    boolean marquerParti() {
        return parti.compareAndSet(false, true);
    }

    public boolean estParti() {
        return parti.get();
    }

    public String getType() {
        return type;
    }

    public String getAdresse() {
        return adresse;
    }

    public long getDepuis() {
        return depuis;
    }

    public long getMessages() {
        return messages.get();
    }

    void compterMessage() {
        messages.incrementAndGet();
    }

    public String getThread() {
        return thread;
    }

    public void setThread(String thread) {
        this.thread = thread;
    }
}
