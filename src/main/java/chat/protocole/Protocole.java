package chat.protocole;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Protocole texte du chat, partagé par le serveur et les clients.
 *
 * Une ligne = un message. Les champs sont séparés par « | » et le texte
 * libre est toujours le dernier champ : il peut donc contenir des « | ».
 *
 * Serveur -> client :
 *   PSEUDO                          le serveur attend un pseudonyme
 *   OK|pseudo                       pseudonyme accepté
 *   ERREUR|texte                    pseudonyme refusé ou commande invalide
 *   MSG|hh:mm:ss|pseudo|texte       message public
 *   HISTO|hh:mm:ss|pseudo|texte     message public envoyé avant l'arrivée du client
 *   PRIVE|hh:mm:ss|de|a|texte       message privé (reçu par l'expéditeur et le destinataire)
 *   INFO|hh:mm:ss|texte             information du serveur (arrivée, départ, /list...)
 *   USERS|p1,p2,p3                  liste des connectés, envoyée à chaque arrivée ou départ
 *   BYE                             le serveur ferme la connexion
 *
 * Client -> serveur : le pseudonyme, puis du texte ou une commande
 * (/list, /msg pseudo texte, /help, /quit ou exit).
 */
public final class Protocole {

    public static final String SEP = "|";

    public static final String PSEUDO = "PSEUDO";
    public static final String OK = "OK";
    public static final String ERREUR = "ERREUR";
    public static final String MSG = "MSG";
    public static final String HISTO = "HISTO";
    public static final String PRIVE = "PRIVE";
    public static final String INFO = "INFO";
    public static final String USERS = "USERS";
    public static final String BYE = "BYE";

    public static final int PSEUDO_MAX = 20;
    public static final int MESSAGE_MAX = 500;
    public static final int HISTORIQUE_MAX = 20;

    private static final Pattern PSEUDO_VALIDE =
            Pattern.compile("[\\p{L}\\p{N}_-]{1," + PSEUDO_MAX + "}");

    private static final DateTimeFormatter HEURE =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private Protocole() {
    }

    /** Construit une ligne : type|champ1|champ2... */
    public static String ligne(String type, String... champs) {
        StringBuilder sb = new StringBuilder(type);
        for (String champ : champs) {
            sb.append(SEP).append(champ);
        }
        return sb.toString();
    }

    /**
     * Découpe une ligne en au plus {@code champs} morceaux : le dernier
     * garde les « | » éventuels du texte.
     */
    public static String[] decouper(String ligne, int champs) {
        return ligne.split(Pattern.quote(SEP), champs);
    }

    public static String type(String ligne) {
        int i = ligne.indexOf(SEP);
        return i < 0 ? ligne : ligne.substring(0, i);
    }

    public static String heure() {
        return LocalTime.now().format(HEURE);
    }

    public static boolean pseudoValide(String pseudo) {
        return PSEUDO_VALIDE.matcher(pseudo).matches();
    }

    /** Clé d'unicité : « Jack » et « jack » sont le même pseudonyme. */
    public static String cle(String pseudo) {
        return pseudo.toLowerCase(Locale.ROOT);
    }

    /**
     * Retire les caractères de contrôle (retours à la ligne compris),
     * les espaces en bord et coupe à {@link #MESSAGE_MAX} caractères.
     */
    public static String nettoyer(String texte) {
        if (texte == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texte.length());
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        String propre = sb.toString().trim();
        if (propre.length() > MESSAGE_MAX) {
            propre = propre.substring(0, MESSAGE_MAX).trim();
        }
        return propre;
    }

    public static boolean estDepart(String texte) {
        return texte.equalsIgnoreCase("exit") || texte.equalsIgnoreCase("/quit");
    }
}
