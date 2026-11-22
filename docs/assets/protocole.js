// Protocole texte du chat : mêmes règles que chat.protocole.Protocole (Java).
// Une ligne = un message ; champs séparés par « | », le texte libre est le dernier champ.

export const PSEUDO_MAX = 20;
export const MESSAGE_MAX = 500;
export const HISTORIQUE_MAX = 20;

export const TEXTES = {
  pseudoVide: "Le pseudo ne peut pas être vide.",
  pseudoInvalide: "Pseudo invalide : 1 à 20 caractères, lettres, chiffres, _ ou -.",
  pseudoPris: "Ce pseudonyme est déjà utilisé.",
  complet: "Serveur complet, réessayez plus tard.",
  aide: "Commandes : /list (connectés), /msg <pseudo> <texte> (message privé), " +
        "/help (aide), /quit ou exit (quitter).",
};

const PSEUDO_VALIDE = new RegExp(`^[\\p{L}\\p{N}_-]{1,${PSEUDO_MAX}}$`, "u");

export function ligne(type, ...champs) {
  return [type, ...champs].join("|");
}

/** Découpe en au plus n champs, le dernier garde les « | » (comme String.split(..., n)). */
export function decouper(l, n) {
  const parts = [];
  let reste = l;
  while (parts.length < n - 1) {
    const i = reste.indexOf("|");
    if (i < 0) break;
    parts.push(reste.slice(0, i));
    reste = reste.slice(i + 1);
  }
  parts.push(reste);
  return parts;
}

/** String.trim() de Java : retire seulement les caractères <= U+0020 aux extrémités. */
export function trimJava(s) {
  let a = 0, b = s.length;
  while (a < b && s.charCodeAt(a) <= 0x20) a++;
  while (b > a && s.charCodeAt(b - 1) <= 0x20) b--;
  return s.slice(a, b);
}

export function nettoyer(texte) {
  if (texte == null) return "";
  let propre = trimJava(String(texte).replace(/[\u0000-\u001f\u007f]/g, " "));
  if (propre.length > MESSAGE_MAX) propre = trimJava(propre.slice(0, MESSAGE_MAX));
  return propre;
}

export const pseudoValide = (p) => PSEUDO_VALIDE.test(p);
export const cle = (p) => p.toLowerCase();
export const estDepart = (t) => t.toLowerCase() === "exit" || t.toLowerCase() === "/quit";

export function heure(date = new Date()) {
  const d2 = (n) => String(n).padStart(2, "0");
  return `${d2(date.getHours())}:${d2(date.getMinutes())}:${d2(date.getSeconds())}`;
}

/** Comparaison des pseudos : minuscules, puis ordre des unités UTF-16 (comme Java). */
export function comparerPseudos(a, b) {
  const x = a.toLowerCase(), y = b.toLowerCase();
  return x < y ? -1 : x > y ? 1 : 0;
}

/** Ligne du protocole -> objet lisible par l'interface. */
export function analyser(l) {
  const type = l.includes("|") ? l.slice(0, l.indexOf("|")) : l;
  let c;
  switch (type) {
    case "MSG":
    case "HISTO":
      c = decouper(l, 4);
      return { type, heure: c[1], de: c[2], texte: c[3] };
    case "PRIVE":
      c = decouper(l, 5);
      return { type, heure: c[1], de: c[2], a: c[3], texte: c[4] };
    case "INFO":
      c = decouper(l, 3);
      return { type, heure: c[1], texte: c[2] };
    case "USERS":
      c = decouper(l, 2);
      return { type, pseudos: c[1] ? c[1].split(",") : [] };
    case "OK":
      return { type, pseudo: decouper(l, 2)[1] };
    case "ERREUR":
      return { type, texte: decouper(l, 2)[1] };
    default:
      return { type, texte: l };
  }
}

/** Masque l'heure des lignes horodatées (pour comparer deux exécutions). */
export function sansHeure(l) {
  const c = l.split("|");
  if (["MSG", "HISTO", "PRIVE", "INFO"].includes(c[0])) c[1] = "hh:mm:ss";
  return c.join("|");
}
