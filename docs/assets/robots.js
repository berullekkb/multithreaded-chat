// Clients simulés de la démo : ils parlent le même protocole qu'un client console
// (PSEUDO -> pseudo -> OK...) et répondent aux messages des humains.

import { analyser } from "./protocole.js";

export const NOMS = ["Sarah", "Youcef", "Amina", "Lucas", "Chloé", "Mehdi", "Inès", "Hugo"];

const REPONSES = [
  [/thread/i,
    () => "Côté serveur, chaque client a son propre thread : quand le mien attend sur readLine(), le tien continue."],
  [/socket|tcp|port/i,
    () => "Les clients console passent par un socket TCP sur le port 5000 ; ton navigateur passe par la passerelle HTTP."],
  [/ordre|verrou|lock|synchron/i,
    () => "Toutes les diffusions passent par un même verrou : on reçoit tous les messages dans le même ordre."],
  [/\b(bonjour|salut|hello|coucou|yo)\b/i,
    (p) => `Salut ${p} ! Ton message vient de passer par le serveur, comme tous les autres.`],
  [/\?\s*$/,
    (p, moi) => `Bonne question ! Essaie /help, ou écris-moi en privé avec /msg ${moi} ...`],
];

const RELANCES = [
  (p) => `Bien reçu, ${p}.`,
  () => "Ajoute un client simulé avec le bouton à droite : tu verras apparaître un nouveau thread.",
  () => "Tape /list pour voir qui est connecté.",
  () => "Regarde la colonne « Dans le serveur » : chaque message part de l'expéditeur, puis le serveur le renvoie à tout le monde.",
];

function choisir(texte, auteur, moi, i) {
  for (const [motif, reponse] of REPONSES) if (motif.test(texte)) return reponse(auteur, moi);
  return RELANCES[i % RELANCES.length](auteur, moi);
}

export class Robots {
  constructor(serveur, { delai = [900, 2200] } = {}) {
    this.serveur = serveur;
    this.delai = delai;
    this.robots = [];
    this.tour = 0;
  }

  noms() {
    return this.robots.map((r) => r.pseudo);
  }

  ajouter() {
    const nom = this._nomLibre();
    if (!nom) return null;
    const robot = { pseudo: nom, connexion: null, essais: 0 };
    robot.connexion = this.serveur.connecter((l) => this._recevoir(robot, l));
    this.robots.push(robot);
    return nom;
  }

  retirer() {
    const robot = this.robots.at(-1);
    if (robot) robot.connexion.envoyer("exit"); // BYE le retire de la liste
    return robot?.pseudo ?? null;
  }

  _nomLibre(exclus = []) {
    const pris = new Set([
      ...this.serveur.status().participants.map((p) => p.pseudo),
      ...this.robots.map((r) => r.pseudo), ...exclus,
    ].map((n) => n.toLowerCase()));
    return NOMS.find((n) => !pris.has(n.toLowerCase())) ?? null;
  }

  _estRobot(pseudo) {
    return this.robots.some((r) => r.pseudo === pseudo);
  }

  _plus_tard(f) {
    const [a, b] = this.delai;
    setTimeout(f, a + Math.random() * (b - a));
  }

  _recevoir(robot, l) {
    if (l === "PSEUDO") {
      // différé : connecter() n'a pas encore rendu la connexion à ce moment-là
      queueMicrotask(() => robot.connexion.envoyer(robot.pseudo));
      return;
    }
    if (l === "BYE") {
      this.robots = this.robots.filter((r) => r !== robot);
      return;
    }
    if (l.startsWith("ERREUR|") && robot.essais < NOMS.length) {
      // pseudo pris entre-temps (par exemple par l'utilisateur) : on en essaie un autre
      robot.essais++;
      const nom = this._nomLibre([robot.pseudo]);
      if (nom) robot.pseudo = nom;
      else { robot.essais = NOMS.length; robot.connexion.envoyer("exit"); }
      return;
    }
    const m = analyser(l);
    if (m.type === "MSG" && !this._estRobot(m.de)) {
      // tous les robots reçoivent la même ligne : un seul répond, à tour de rôle
      if (this.dejaTraitee === l) return;
      this.dejaTraitee = l;
      const repondant = this.robots[this.tour % this.robots.length];
      const i = this.tour++;
      this._plus_tard(() => repondant.connexion.envoyer(choisir(m.texte, m.de, repondant.pseudo, i)));
    } else if (m.type === "PRIVE" && m.a === robot.pseudo && !this._estRobot(m.de)) {
      this._plus_tard(() => robot.connexion.envoyer(
        `/msg ${m.de} Message privé bien reçu : seuls toi et moi le voyons passer.`));
    } else if (m.type === "INFO" && / a rejoint la conversation\.$/.test(m.texte)) {
      const arrivant = m.texte.replace(/ a rejoint la conversation\.$/, "");
      if (!this._estRobot(arrivant) && this.robots[0] === robot) {
        this._plus_tard(() => robot.connexion.envoyer(
          `Bienvenue ${arrivant} ! Je suis un client simulé, mais je passe par le même protocole que toi.`));
      }
    }
  }
}
