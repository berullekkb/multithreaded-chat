package chat.server;

import static chat.protocole.Protocole.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * État partagé du chat : participants, historique et diffusion.
 *
 * Plusieurs threads (un par client) appellent ces méthodes en même temps.
 * - La table des participants est une {@link ConcurrentHashMap} :
 *   {@code putIfAbsent} réserve un pseudonyme de façon atomique.
 * - Toutes les diffusions passent par un même verrou : chaque client
 *   reçoit donc les messages exactement dans le même ordre, et un nouvel
 *   arrivant ne peut pas « rater » un message entre son historique et
 *   sa première diffusion.
 */
public class SalonDeChat {

    public static final String ERR_PSEUDO_VIDE = "Le pseudo ne peut pas être vide.";
    public static final String ERR_PSEUDO_INVALIDE =
            "Pseudo invalide : 1 à 20 caractères, lettres, chiffres, _ ou -.";
    public static final String ERR_PSEUDO_PRIS = "Ce pseudonyme est déjà utilisé.";
    public static final String ERR_COMPLET = "Serveur complet, réessayez plus tard.";
    public static final String AIDE =
            "Commandes : /list (connectés), /msg <pseudo> <texte> (message privé), "
            + "/help (aide), /quit ou exit (quitter).";

    private static final Comparator<String> ORDRE_PSEUDOS =
            Comparator.comparing(p -> p.toLowerCase(Locale.ROOT));

    private final ConcurrentHashMap<String, Participant> participants =
            new ConcurrentHashMap<>();

    /** Derniers messages publics : protégé par {@link #verrou}. */
    private final Deque<String[]> historique = new ArrayDeque<>();

    private final Object verrou = new Object();
    private final AtomicLong messagesDiffuses = new AtomicLong();
    private final long demarrage = System.currentTimeMillis();
    private final int maxParticipants;
    private final Journal journal;

    public SalonDeChat(int maxParticipants, Journal journal) {
        this.maxParticipants = maxParticipants;
        this.journal = journal;
    }

    /**
     * Tente de réserver un pseudonyme.
     *
     * @return null si le pseudonyme est accordé, sinon le message d'erreur.
     */
    public String reserver(String pseudo, Participant p) {
        if (pseudo == null || pseudo.trim().isEmpty()) {
            return ERR_PSEUDO_VIDE;
        }
        pseudo = pseudo.trim();
        if (!pseudoValide(pseudo) || estDepart(pseudo)) {
            return ERR_PSEUDO_INVALIDE;
        }
        if (participants.size() >= maxParticipants) {
            return ERR_COMPLET;
        }
        // Opération atomique : deux clients ne peuvent pas obtenir le même pseudo.
        if (participants.putIfAbsent(cle(pseudo), p) != null) {
            return ERR_PSEUDO_PRIS;
        }
        p.setPseudo(pseudo);
        return null;
    }

    /** Fait entrer un participant dont le pseudonyme a été réservé. */
    public void rejoindre(Participant p) {
        String pseudo = p.getPseudo();
        synchronized (verrou) {
            p.envoyer(ligne(OK, pseudo));
            for (String[] h : historique) {
                p.envoyer(ligne(HISTO, h[0], h[1], h[2]));
            }
            p.envoyer(ligne(INFO, heure(),
                    "Bienvenue " + pseudo + " ! Tapez /help pour voir les commandes."));
            p.activer();
            diffuser(ligne(INFO, heure(), pseudo + " a rejoint la conversation."));
            diffuser(ligne(USERS, String.join(",", pseudosActifs())));
        }
        journal.ecrire(pseudo + " est connecté (" + p.getType() + ", " + p.getAdresse() + ").");
    }

    /**
     * Traite une ligne envoyée par un participant actif.
     *
     * @return false si le participant demande à quitter.
     */
    public boolean traiter(Participant p, String brut) {
        String texte = nettoyer(brut);
        if (texte.isEmpty()) {
            return true;
        }
        if (estDepart(texte)) {
            return false;
        }
        journal.ecrire(p.getPseudo() + " -> " + texte);
        if (texte.startsWith("/")) {
            commande(p, texte);
            return true;
        }
        synchronized (verrou) {
            String heure = heure();
            historique.addLast(new String[] {heure, p.getPseudo(), texte});
            while (historique.size() > HISTORIQUE_MAX) {
                historique.removeFirst();
            }
            diffuser(ligne(MSG, heure, p.getPseudo(), texte));
        }
        p.compterMessage();
        messagesDiffuses.incrementAndGet();
        return true;
    }

    /** Retire un participant. Peut être appelé plusieurs fois sans effet. */
    public void quitter(Participant p) {
        if (!p.marquerParti()) {
            return;
        }
        String pseudo = p.getPseudo();
        if (pseudo == null) {
            return;
        }
        participants.remove(cle(pseudo), p);
        if (p.estActif()) {
            synchronized (verrou) {
                diffuser(ligne(INFO, heure(), pseudo + " a quitté la conversation."));
                diffuser(ligne(USERS, String.join(",", pseudosActifs())));
            }
            journal.ecrire(pseudo + " s'est déconnecté.");
        }
    }

    /** Arrêt du serveur : prévient tout le monde puis ferme les connexions. */
    public void arreter() {
        synchronized (verrou) {
            diffuser(ligne(INFO, heure(), "Le serveur s'arrête. À bientôt !"));
            diffuser(BYE);
        }
        for (Participant p : participants.values()) {
            p.fermer();
        }
    }

    private void commande(Participant p, String texte) {
        String[] mots = texte.split("\\s+", 3);
        String nom = mots[0].toLowerCase(Locale.ROOT);
        switch (nom) {
            case "/help":
                p.envoyer(ligne(INFO, heure(), AIDE));
                break;
            case "/list": {
                List<String> actifs = pseudosActifs();
                p.envoyer(ligne(INFO, heure(),
                        "Connectés (" + actifs.size() + ") : " + String.join(", ", actifs)));
                break;
            }
            case "/msg":
                messagePrive(p, mots);
                break;
            default:
                p.envoyer(ligne(ERREUR, "Commande inconnue : " + mots[0] + " (tapez /help)"));
        }
    }

    private void messagePrive(Participant p, String[] mots) {
        if (mots.length < 3 || mots[2].trim().isEmpty()) {
            p.envoyer(ligne(ERREUR, "Usage : /msg <pseudo> <texte>"));
            return;
        }
        Participant dest = participants.get(cle(mots[1]));
        if (dest == null || !dest.estActif()) {
            p.envoyer(ligne(ERREUR, "Utilisateur introuvable : " + mots[1]));
            return;
        }
        if (dest == p) {
            p.envoyer(ligne(ERREUR, "Vous ne pouvez pas vous écrire à vous-même."));
            return;
        }
        String l = ligne(PRIVE, heure(), p.getPseudo(), dest.getPseudo(), mots[2].trim());
        synchronized (verrou) {
            dest.envoyer(l);
            p.envoyer(l);
        }
        p.compterMessage();
        messagesDiffuses.incrementAndGet();
    }

    /** À appeler en tenant le verrou. */
    private void diffuser(String ligne) {
        for (Participant q : participants.values()) {
            if (q.estActif()) {
                q.envoyer(ligne);
            }
        }
    }

    public List<String> pseudosActifs() {
        List<String> liste = new ArrayList<>();
        for (Participant p : participants.values()) {
            if (p.estActif()) {
                liste.add(p.getPseudo());
            }
        }
        liste.sort(ORDRE_PSEUDOS);
        return liste;
    }

    public List<Participant> participantsActifs() {
        List<Participant> liste = new ArrayList<>();
        for (Participant p : participants.values()) {
            if (p.estActif()) {
                liste.add(p);
            }
        }
        liste.sort(Comparator.comparing(Participant::getPseudo, ORDRE_PSEUDOS));
        return liste;
    }

    public long getMessagesDiffuses() {
        return messagesDiffuses.get();
    }

    public long getDemarrage() {
        return demarrage;
    }

    public int getMaxParticipants() {
        return maxParticipants;
    }

    /** Petit journal console horodaté, remplaçable dans les tests. */
    public interface Journal {
        void ecrire(String message);
    }
}
