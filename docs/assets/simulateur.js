// Serveur simulé dans le navigateur, pour la démo sur GitHub Pages.
// Il reproduit ligne pour ligne le comportement du serveur Java
// (SalonDeChat + ClientHandler) ; tests/js/parite.test.mjs le vérifie
// contre une session réellement enregistrée sur le serveur Java.

import {
  TEXTES, HISTORIQUE_MAX, ligne, nettoyer, pseudoValide, cle, estDepart, heure,
  comparerPseudos, trimJava,
} from "./protocole.js";

/** Comme String.split("\\s+", n) en Java. */
function separer(s, n) {
  const parts = [];
  let reste = s;
  while (parts.length < n - 1) {
    const m = /\s+/.exec(reste);
    if (!m) break;
    parts.push(reste.slice(0, m.index));
    reste = reste.slice(m.index + m[0].length);
  }
  parts.push(reste);
  return parts;
}

class Connexion {
  constructor(serveur, recevoir, type, thread) {
    this.serveur = serveur;
    this.recevoir = recevoir;
    this.type = type;
    this.thread = thread;
    this.pseudo = null;
    this.actif = false;
    this.parti = false;
    this.ferme = false;
    this.messages = 0;
    this.depuis = Date.now();
  }

  /** Ligne envoyée par le client au serveur. */
  envoyer(brut) {
    if (this.ferme) return;
    this.serveur._ligneRecue(this, brut);
  }

  /** Fermeture brutale côté client (onglet fermé, réseau coupé). */
  fermer() {
    if (this.ferme) return;
    this.serveur._quitter(this);
    this.ferme = true;
  }

  _livrer(l) {
    if (!this.ferme) this.recevoir(l);
  }
}

export class ServeurSimule {
  constructor({ max = 50, portTcp = 5000, portWeb = 8080 } = {}) {
    this.max = max;
    this.portTcp = portTcp;
    this.portWeb = portWeb;
    this.participants = new Map(); // clé minuscule -> Connexion
    this.historique = [];
    this.messagesDiffuses = 0;
    this.demarrage = Date.now();
    this.numeroTcp = 0;
    this.numeroWeb = 0;
    this.connexions = new Set();
  }

  /** Nouvelle connexion : le serveur demande aussitôt un pseudo (comme ClientHandler). */
  connecter(recevoir, { type = "tcp" } = {}) {
    const thread = type === "web" ? `web-${++this.numeroWeb}` : `client-tcp-${++this.numeroTcp}`;
    const c = new Connexion(this, recevoir, type, thread);
    this.connexions.add(c);
    if (this.connexions.size > this.max) {
      c._livrer(ligne("ERREUR", TEXTES.complet));
      c._livrer("BYE");
      c.ferme = true;
      this.connexions.delete(c);
      return c;
    }
    c._livrer("PSEUDO");
    return c;
  }

  status() {
    const maintenant = Date.now();
    const actifs = this._actifs();
    return {
      mode: "demo",
      port_tcp: this.portTcp,
      port_web: this.portWeb,
      uptime_s: Math.floor((maintenant - this.demarrage) / 1000),
      messages: this.messagesDiffuses,
      // main + accept + nettoyeur web + un thread par connexion
      threads_actifs: 3 + this.connexions.size,
      max: this.max,
      participants: actifs.map((p) => ({
        pseudo: p.pseudo, type: p.type, thread: p.thread,
        connecte_s: Math.floor((maintenant - p.depuis) / 1000), messages: p.messages,
      })),
    };
  }

  // ---------------------------------------------------------- ClientHandler

  _ligneRecue(c, brut) {
    if (!c.actif) {
      // phase pseudonyme
      if (estDepart(trimJava(brut))) {
        this._depart(c);
        return;
      }
      const erreur = this._reserver(brut, c);
      if (erreur) {
        c._livrer(ligne("ERREUR", erreur));
        c._livrer("PSEUDO");
        return;
      }
      this._rejoindre(c);
      return;
    }
    if (!this._traiter(c, brut)) this._depart(c);
  }

  _depart(c) {
    this._quitter(c);
    c._livrer("BYE");
    c.ferme = true;
    this.connexions.delete(c);
  }

  // ------------------------------------------------------------- SalonDeChat

  _reserver(pseudo, c) {
    if (pseudo == null || trimJava(pseudo) === "") return TEXTES.pseudoVide;
    pseudo = trimJava(pseudo);
    if (!pseudoValide(pseudo) || estDepart(pseudo)) return TEXTES.pseudoInvalide;
    if (this.participants.size >= this.max) return TEXTES.complet;
    if (this.participants.has(cle(pseudo))) return TEXTES.pseudoPris;
    this.participants.set(cle(pseudo), c);
    c.pseudo = pseudo;
    return null;
  }

  _rejoindre(c) {
    c._livrer(ligne("OK", c.pseudo));
    for (const h of this.historique) c._livrer(ligne("HISTO", ...h));
    c._livrer(ligne("INFO", heure(), `Bienvenue ${c.pseudo} ! Tapez /help pour voir les commandes.`));
    c.actif = true;
    this._diffuser(ligne("INFO", heure(), `${c.pseudo} a rejoint la conversation.`));
    this._diffuser(ligne("USERS", this._pseudosActifs().join(",")));
  }

  _traiter(c, brut) {
    const texte = nettoyer(brut);
    if (texte === "") return true;
    if (estDepart(texte)) return false;
    if (texte.startsWith("/")) {
      this._commande(c, texte);
      return true;
    }
    const h = heure();
    this.historique.push([h, c.pseudo, texte]);
    while (this.historique.length > HISTORIQUE_MAX) this.historique.shift();
    this._diffuser(ligne("MSG", h, c.pseudo, texte));
    c.messages++;
    this.messagesDiffuses++;
    return true;
  }

  _quitter(c) {
    this.connexions.delete(c);
    if (c.parti) return;
    c.parti = true;
    if (c.pseudo == null) return;
    if (this.participants.get(cle(c.pseudo)) === c) this.participants.delete(cle(c.pseudo));
    if (c.actif) {
      this._diffuser(ligne("INFO", heure(), `${c.pseudo} a quitté la conversation.`));
      this._diffuser(ligne("USERS", this._pseudosActifs().join(",")));
    }
  }

  _commande(c, texte) {
    const mots = separer(texte, 3);
    switch (mots[0].toLowerCase()) {
      case "/help":
        c._livrer(ligne("INFO", heure(), TEXTES.aide));
        break;
      case "/list": {
        const actifs = this._pseudosActifs();
        c._livrer(ligne("INFO", heure(), `Connectés (${actifs.length}) : ${actifs.join(", ")}`));
        break;
      }
      case "/msg":
        this._prive(c, mots);
        break;
      default:
        c._livrer(ligne("ERREUR", `Commande inconnue : ${mots[0]} (tapez /help)`));
    }
  }

  _prive(c, mots) {
    if (mots.length < 3 || trimJava(mots[2]) === "") {
      c._livrer(ligne("ERREUR", "Usage : /msg <pseudo> <texte>"));
      return;
    }
    const dest = this.participants.get(cle(mots[1]));
    if (!dest || !dest.actif) {
      c._livrer(ligne("ERREUR", `Utilisateur introuvable : ${mots[1]}`));
      return;
    }
    if (dest === c) {
      c._livrer(ligne("ERREUR", "Vous ne pouvez pas vous écrire à vous-même."));
      return;
    }
    const l = ligne("PRIVE", heure(), c.pseudo, dest.pseudo, trimJava(mots[2]));
    dest._livrer(l);
    c._livrer(l);
    c.messages++;
    this.messagesDiffuses++;
  }

  _diffuser(l) {
    for (const p of this.participants.values()) if (p.actif) p._livrer(l);
  }

  _actifs() {
    return [...this.participants.values()].filter((p) => p.actif)
      .sort((a, b) => comparerPseudos(a.pseudo, b.pseudo));
  }

  _pseudosActifs() {
    return this._actifs().map((p) => p.pseudo);
  }
}
