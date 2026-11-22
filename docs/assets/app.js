// Interface web du chat.
// - Servie par le serveur Java (http://localhost:8080) : mode « en direct », on rejoint le vrai salon.
// - Sur GitHub Pages : mode « démo », le serveur est simulé dans la page (simulateur.js).

import { analyser } from "./protocole.js";
import { ServeurSimule } from "./simulateur.js";
import { Robots } from "./robots.js";

const $ = (id) => document.getElementById(id);
const SVG = "http://www.w3.org/2000/svg";
const reduit = matchMedia("(prefers-reduced-motion: reduce)").matches;

const COULEURS = ["#E4572E", "#29A39B", "#F3A712", "#9B8AF5", "#4F9BD9", "#E05FA6", "#6DBA55", "#D08F5B"];
// Une couleur de câble par pseudo, attribuée à la première apparition (pas de doublon avant 8 personnes).
const couleurs = new Map();
function couleur(pseudo) {
  const k = pseudo.toLowerCase();
  if (!couleurs.has(k)) couleurs.set(k, COULEURS[couleurs.size % COULEURS.length]);
  return couleurs.get(k);
}

function el(tag, attrs = {}, ...enfants) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k === "style") e.style.cssText = v;
    else e.setAttribute(k, v);
  }
  for (const x of enfants) if (x != null) e.append(x);
  return e;
}
function svg(tag, attrs = {}, texte) {
  const e = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  if (texte != null) e.textContent = texte;
  return e;
}

// =================================================================== transports

class TransportLive {
  constructor() {
    this.mode = "live";
    this.jeton = null;
    this.source = null;
  }
  async status() {
    const r = await fetch("api/status", { cache: "no-store" });
    return r.json();
  }
  async rejoindre(pseudo) {
    const r = await fetch("api/join", { method: "POST", body: new URLSearchParams({ pseudo }) });
    const j = await r.json();
    if (!j.ok) return j;
    this.jeton = j.jeton;
    this.source = new EventSource("api/flux?jeton=" + encodeURIComponent(j.jeton));
    this.source.onmessage = (e) => this.onLigne(e.data);
    this.source.addEventListener("fin", () => this.source.close());
    this.source.onerror = () => {
      if (this.source.readyState === EventSource.CLOSED) this.onPerte("La connexion avec le serveur est perdue.");
    };
    return j;
  }
  envoyer(texte) {
    fetch("api/send", { method: "POST", body: new URLSearchParams({ jeton: this.jeton, texte }) })
      .then((r) => { if (r.status === 410) this.onPerte("Session terminée par le serveur."); })
      .catch(() => this.onPerte("Le serveur ne répond plus."));
  }
  quitter() {
    this.envoyer("/quit");
  }
  abandonner() {
    if (this.jeton) navigator.sendBeacon("api/leave", new URLSearchParams({ jeton: this.jeton }));
  }
}

class TransportDemo {
  constructor() {
    this.mode = "demo";
    this.serveur = new ServeurSimule();
    this.robots = new Robots(this.serveur);
    this.conn = null;
  }
  async status() {
    return this.serveur.status();
  }
  rejoindre(pseudo) {
    // Même déroulé qu'un client TCP : PSEUDO -> pseudo -> OK ou ERREUR.
    return new Promise((resolve) => {
      let pseudoEnvoye = false;
      let dansLeSalon = false;
      const conn = this.serveur.connecter((l) => {
        this.onLigne(l);
        if (dansLeSalon) return;
        if (l === "PSEUDO") {
          if (pseudoEnvoye) { conn.fermer(); return; } // refusé : on raccroche
          pseudoEnvoye = true;
          queueMicrotask(() => conn.envoyer(pseudo));
        } else if (l.startsWith("ERREUR|")) {
          resolve({ ok: false, erreur: l.slice(7) });
        } else if (l.startsWith("OK|")) {
          dansLeSalon = true;
          this.conn = conn;
          resolve({ ok: true, pseudo: l.slice(3) });
        }
      }, { type: "web" });
    });
  }
  envoyer(texte) {
    this.conn?.envoyer(texte);
  }
  quitter() {
    this.conn?.envoyer("exit");
  }
  abandonner() {
    this.conn?.fermer();
  }
}

async function detecterServeur() {
  // GitHub Pages n'héberge que des fichiers : inutile de chercher le serveur Java.
  if (location.hostname.endsWith("github.io") || location.protocol === "file:") return new TransportDemo();
  try {
    const ctrl = new AbortController();
    const minuteur = setTimeout(() => ctrl.abort(), 2000);
    const r = await fetch("api/status", { cache: "no-store", signal: ctrl.signal });
    clearTimeout(minuteur);
    if (r.ok && (await r.json()).mode === "live") return new TransportLive();
  } catch { /* pas de serveur Java : démo */ }
  return new TransportDemo();
}

// =================================================================== salon

let transport;
let moi = null;
let participants = [];
let signature = "";
let departDemande = false;

function journal(sens, ligne) {
  const j = $("journal");
  const type = sens === "→" ? "" : ligne.split("|")[0];
  const reste = sens === "→" ? ligne : ligne.slice(type.length);
  const li = el("li", { class: sens === "→" ? "sortant" : "" },
    el("span", { class: "sens", "aria-label": sens === "→" ? "envoyé" : "reçu" }, sens),
    el("span", {}, type ? el("span", { class: "type" }, type) : null, reste));
  const enBas = j.scrollHeight - j.scrollTop - j.clientHeight < 30;
  j.append(li);
  while (j.children.length > 200) j.firstChild.remove();
  if (enBas) j.scrollTop = j.scrollHeight;
}

function ajouterAuFil(li) {
  const fil = $("fil");
  const enBas = fil.scrollHeight - fil.scrollTop - fil.clientHeight < 60;
  fil.append(li);
  while (fil.children.length > 400) fil.firstChild.remove();
  if (enBas || li.classList.contains("moi")) fil.scrollTop = fil.scrollHeight;
}

function message(m) {
  const c = couleur(m.de);
  const li = el("li", { class: [m.de === moi ? "moi" : "", m.type === "HISTO" ? "histo" : ""].join(" ").trim(),
    style: `border-left-color:${c}` },
  el("div", { class: "tete" },
    el("span", { class: "auteur", style: `color:${assombrir(c)}` }, m.de),
    el("time", {}, m.heure)),
  el("p", { class: "texte" }, m.texte));
  ajouterAuFil(li);
}

function prive(m) {
  const autre = m.de === moi ? m.a : m.de;
  const li = el("li", { class: "prive", style: `border-left-color:${couleur(autre)}` },
    el("div", { class: "tete" },
      el("span", { class: "auteur" }, m.de),
      el("span", { class: "vers" }, `en privé à ${m.a === moi ? "vous" : m.a}`),
      el("time", {}, m.heure)),
    el("p", { class: "texte" }, m.texte));
  ajouterAuFil(li);
}

function systeme(texte, classe = "info") {
  ajouterAuFil(el("li", { class: classe }, texte));
}

/** Version plus sombre d'une couleur de câble, lisible sur fond blanc. */
function assombrir(hex) {
  const n = parseInt(hex.slice(1), 16);
  const f = (v) => Math.round(v * 0.72);
  return `rgb(${f(n >> 16)}, ${f((n >> 8) & 255)}, ${f(n & 255)})`;
}

function afficherConnectes(pseudos) {
  const ul = $("connectes");
  ul.replaceChildren(...pseudos.map((p) =>
    el("li", { class: p === moi ? "moi" : "" }, el("i", { style: `background:${couleur(p)}` }),
      p === moi ? `${p} (vous)` : p)));
}

function recevoir(l) {
  journal("←", l);
  const m = analyser(l);
  switch (m.type) {
    case "OK":
      moi = m.pseudo;
      $("formRejoindre").hidden = true;
      $("formMessage").hidden = false;
      $("message").focus();
      break;
    case "MSG":
    case "HISTO":
      message(m);
      if (m.type === "MSG") impulsion(m.de, null);
      break;
    case "PRIVE":
      prive(m);
      impulsion(m.de, [m.de, m.a]);
      break;
    case "INFO":
      systeme(m.texte);
      if (/ a (rejoint|quitté) la conversation\.$/.test(m.texte)) impulsion(null, null);
      break;
    case "USERS":
      afficherConnectes(m.pseudos);
      rafraichir();
      break;
    case "ERREUR":
      if (moi) systeme(m.texte, "erreur");
      else $("erreurPseudo").textContent = m.texte;
      break;
    case "BYE":
      sortieDuSalon(departDemande ? "Vous avez quitté le salon." : "Le serveur a fermé la connexion.");
      break;
  }
}

function sortieDuSalon(texte) {
  if (moi) systeme(texte, "separateur");
  moi = null;
  $("formMessage").hidden = true;
  $("formRejoindre").hidden = false;
  if (transport.mode === "live" && transport.source) transport.source.close();
  afficherConnectes([]);
  rafraichir();
}

// =================================================================== câblage

const LARGEUR = 440;
const X_SERVEUR = 118;
const X_PRISE = LARGEUR - 128;
const PAS = 48;
const Y0 = 46;

function dessinerCablage(liste) {
  const s = $("cablage");
  const hauteur = Math.max(150, Y0 + Math.max(liste.length, 1) * PAS);
  s.setAttribute("viewBox", `0 0 ${LARGEUR} ${hauteur}`);
  s.replaceChildren();
  s.append(svg("rect", { class: "bloc-serveur", x: 8, y: 12, width: X_SERVEUR - 8, height: hauteur - 24, rx: 6 }));
  s.append(svg("text", { class: "titre-serveur", x: 20, y: 38 }, "Serveur"));
  const ports = transport.mode === "live" ? [`:${etat.port_tcp} tcp`, `:${etat.port_web} web`] : ["simulé dans", "la page"];
  s.append(svg("text", { class: "ports", x: 20, y: 56 }, ports[0]));
  s.append(svg("text", { class: "ports", x: 20, y: 70 }, ports[1]));
  if (!liste.length) {
    s.append(svg("text", { class: "vide", x: X_SERVEUR + 20, y: Y0 + 8 }, "Aucun client connecté"));
    return;
  }
  liste.forEach((p, i) => {
    const y = Y0 + i * PAS;
    const c = couleur(p.pseudo);
    const g = svg("g", { "data-pseudo": p.pseudo });
    const d = `M ${X_SERVEUR} ${y} C ${X_SERVEUR + 70} ${y + 14}, ${X_PRISE - 70} ${y + 14}, ${X_PRISE} ${y}`;
    g.append(svg("path", { class: "cable", d, stroke: c }));
    g.append(svg("rect", { x: X_SERVEUR - 4, y: y - 5, width: 8, height: 10, rx: 2, fill: c }));
    g.append(svg("circle", { cx: X_PRISE, cy: y, r: 7, fill: c }));
    g.append(svg("text", { class: "thread", x: X_SERVEUR + 12, y: y - 9 }, p.thread));
    g.append(svg("text", { class: "nom" + (p.pseudo === moi ? " moi" : ""), x: X_PRISE + 14, y: y + 5 },
      p.pseudo === moi ? `${p.pseudo} (vous)` : p.pseudo));
    s.append(g);
  });
}

function cable(pseudo) {
  return [...$("cablage").querySelectorAll("g[data-pseudo]")].find((g) => g.dataset.pseudo === pseudo)?.querySelector("path");
}

/** Un point lumineux remonte le câble de l'expéditeur, puis repart vers les destinataires. */
function impulsion(de, cibles) {
  const entrant = de ? cable(de) : null;
  const sortants = [...$("cablage").querySelectorAll("g[data-pseudo]")]
    .filter((g) => !cibles || cibles.includes(g.dataset.pseudo))
    .map((g) => g.querySelector("path"));
  const teinte = de ? couleur(de) : "#ffffff";
  if (reduit) {
    for (const p of [entrant, ...sortants].filter(Boolean)) {
      p.classList.remove("eclat"); void p.getBBox(); p.classList.add("eclat");
    }
    return;
  }
  const suite = () => sortants.forEach((p) => parcourir(p, false, teinte));
  if (entrant) parcourir(entrant, true, teinte, suite);
  else suite();
}

function parcourir(chemin, versServeur, teinte, fin) {
  const total = chemin.getTotalLength();
  const point = svg("circle", { class: "impulsion", r: 5, fill: teinte, stroke: "#fff", "stroke-width": 1.5 });
  $("cablage").append(point);
  const duree = 380;
  const t0 = performance.now();
  const pas = (t) => {
    const k = Math.min(1, (t - t0) / duree);
    const pos = chemin.getPointAtLength(total * (versServeur ? 1 - k : k));
    point.setAttribute("cx", pos.x);
    point.setAttribute("cy", pos.y);
    if (k < 1) requestAnimationFrame(pas);
    else { point.remove(); fin?.(); }
  };
  requestAnimationFrame(pas);
}

// =================================================================== mesures

let etat = {};
function duree(s) {
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  return `${Math.floor(s / 3600)} h ${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}`;
}

async function rafraichir() {
  try {
    etat = await transport.status();
  } catch {
    $("mode").className = "mode";
    $("mode").textContent = "Serveur injoignable : relancez-le puis rechargez la page.";
    return;
  }
  if (transport.mode === "live") annoncerLive();
  participants = etat.participants;
  if (!moi) afficherConnectes(participants.map((p) => p.pseudo));
  $("mConnectes").textContent = participants.length;
  $("mThreads").textContent = etat.threads_actifs;
  $("mMessages").textContent = etat.messages;
  $("mUptime").textContent = duree(etat.uptime_s);
  const sig = participants.map((p) => p.pseudo + "/" + p.thread).join(",") + "|" + moi;
  if (sig !== signature) {
    signature = sig;
    dessinerCablage(participants);
  }
}

// =================================================================== formulaires

$("formRejoindre").addEventListener("submit", async (e) => {
  e.preventDefault();
  const pseudo = $("pseudo").value.trim();
  $("erreurPseudo").textContent = "";
  departDemande = false;
  journal("→", pseudo);
  const bouton = e.submitter ?? $("formRejoindre").querySelector("button");
  bouton.disabled = true;
  try {
    const r = await transport.rejoindre(pseudo);
    if (!r.ok) $("erreurPseudo").textContent = r.erreur;
  } catch {
    $("erreurPseudo").textContent = "Le serveur ne répond pas. Vérifiez qu'il est toujours lancé.";
  } finally {
    bouton.disabled = false;
  }
});

$("formMessage").addEventListener("submit", (e) => {
  e.preventDefault();
  const texte = $("message").value;
  if (!texte.trim()) return;
  journal("→", texte);
  transport.envoyer(texte);
  $("message").value = "";
});

$("quitter").addEventListener("click", () => {
  departDemande = true;
  journal("→", "exit");
  transport.quitter();
});

$("ajouterRobot").addEventListener("click", () => {
  if (!transport.robots.ajouter()) $("ajouterRobot").disabled = true;
  $("retirerRobot").disabled = false;
});
$("retirerRobot").addEventListener("click", () => {
  transport.robots.retirer();
  $("ajouterRobot").disabled = false;
  if (!transport.robots.noms().length) $("retirerRobot").disabled = true;
});

addEventListener("pagehide", () => transport?.abandonner());

// =================================================================== rejeu de la trace Java

const rejeu = { groupes: [], visible: 0 };

async function preparerRejeu() {
  let trace;
  try {
    trace = await (await fetch("data/trace.json")).json();
  } catch {
    $("legende").textContent = "Trace introuvable (docs/data/trace.json).";
    return;
  }
  $("sourceTrace").textContent = `Trois clients, ${trace.etapes.length} étapes, enregistrées le ${trace.enregistre_le} ` +
    "sur le vrai serveur Java. Chaque flèche est une ligne réellement passée sur un socket.";
  const colonnes = ["Serveur", ...trace.clients];
  const W = 980;
  const xCol = (nom) => 150 + colonnes.indexOf(nom) * ((W - 220) / (colonnes.length - 1));
  const s = $("sequence");
  s.replaceChildren();
  const defs = svg("defs");
  for (const nom of colonnes) {
    const m = svg("marker", { id: `pointe-${nom}`, viewBox: "0 0 10 10", refX: 9, refY: 5,
      markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" });
    m.append(svg("path", { d: "M0 0 L10 5 L0 10 z", fill: nom === "Serveur" ? "#22313A" : couleur(nom) }));
    defs.append(m);
  }
  s.append(defs);
  const fondColonnes = svg("g");
  s.append(fondColonnes);

  let y = 56;
  rejeu.groupes = trace.etapes.map((etape, i) => {
    const g = svg("g", { "data-etape": i });
    g.append(svg("text", { class: "numero", x: 16, y: y + 4 }, `${i + 1}`));
    // ligne envoyée par le client
    const xc = xCol(etape.client);
    const xs = xCol("Serveur");
    g.append(svg("line", { class: "fleche", x1: xc, y1: y, x2: xs + 3, y2: y, stroke: couleur(etape.client),
      "marker-end": `url(#pointe-${etape.client})` }));
    const libelle = etape.action === "connexion" ? "(connexion TCP)" : etape.texte;
    g.append(etiquette(xs + 12, y - 7, libelle));
    y += 30;
    // lignes reçues, regroupées quand plusieurs clients reçoivent la même
    const ordre = [etape.client, ...trace.clients.filter((c) => c !== etape.client)];
    const lignes = [];
    for (const nom of ordre) {
      for (const l of etape.recus[nom] ?? []) {
        const deja = lignes.find((x) => x.ligne === l && !x.dest.includes(nom));
        if (deja) deja.dest.push(nom); else lignes.push({ ligne: l, dest: [nom] });
      }
    }
    for (const { ligne, dest } of lignes) {
      const xmax = Math.max(...dest.map(xCol));
      g.append(svg("line", { class: "fleche", x1: xs, y1: y, x2: xmax - 3, y2: y, stroke: "#22313A",
        "marker-end": "url(#pointe-Serveur)" }));
      for (const d of dest) if (xCol(d) !== xmax) g.append(svg("circle", { cx: xCol(d), cy: y, r: 4, fill: "#22313A" }));
      g.append(etiquette(xs + 12, y - 7, ligne));
      y += 28;
    }
    y += 12;
    return { g, bas: y, legende: etape.legende };
  });

  rejeu.colonnes = { liste: colonnes, xCol, fond: fondColonnes, W };
  remettreRejeu();
}

function etiquette(x, y, texte) {
  const t = svg("text", { class: "etiquette", x, y }, texte);
  t.setAttribute("paint-order", "stroke");
  t.setAttribute("stroke", "#fff");
  t.setAttribute("stroke-width", "5");
  t.setAttribute("stroke-linejoin", "round");
  return t;
}

function afficherRejeu() {
  const s = $("sequence");
  const { liste, xCol, fond, W } = rejeu.colonnes;
  const vus = rejeu.groupes.slice(0, rejeu.visible);
  const bas = vus.length ? vus.at(-1).bas : 70;
  s.setAttribute("viewBox", `0 0 ${W} ${bas}`);
  fond.replaceChildren();
  for (const nom of liste) {
    fond.append(svg("line", { class: "colonne", x1: xCol(nom), y1: 34, x2: xCol(nom), y2: bas }));
    fond.append(svg("text", { class: "nom-colonne", x: xCol(nom), y: 24, "text-anchor": "middle",
      fill: nom === "Serveur" ? "#15212a" : assombrir(couleur(nom)) }, nom));
  }
  rejeu.groupes.forEach(({ g }, i) => {
    if (i < rejeu.visible) {
      if (!g.isConnected) s.append(g);
      g.classList.toggle("ancienne", i < rejeu.visible - 1);
    } else g.remove();
  });
  const courant = rejeu.groupes[rejeu.visible - 1];
  $("legende").textContent = courant ? `${rejeu.visible}. ${courant.legende}` :
    "Appuyez sur « Étape suivante » pour dérouler la session.";
  $("etapeSuivante").disabled = rejeu.visible >= rejeu.groupes.length;
}

function remettreRejeu() {
  rejeu.visible = 0;
  afficherRejeu();
}

$("etapeSuivante").addEventListener("click", () => { rejeu.visible++; afficherRejeu(); });
$("toutAfficher").addEventListener("click", () => { rejeu.visible = rejeu.groupes.length; afficherRejeu(); });
$("recommencer").addEventListener("click", remettreRejeu);

// =================================================================== démarrage

function annoncerLive() {
  const mode = $("mode");
  mode.className = "mode live";
  mode.textContent = "Connecté au serveur Java : les clients console rejoignent le même salon" +
    (etat.port_tcp ? ` (port ${etat.port_tcp}).` : ".");
}

async function demarrer() {
  transport = await detecterServeur();
  transport.onLigne = recevoir;
  transport.onPerte = (texte) => sortieDuSalon(texte);
  const mode = $("mode");
  if (transport.mode === "live") {
    annoncerLive();
    setInterval(rafraichir, 3000);
  } else {
    mode.classList.add("demo");
    mode.textContent = "Démo : le serveur est simulé dans votre navigateur, avec deux clients simulés.";
    $("actionsDemo").hidden = false;
    transport.robots.ajouter();
    transport.robots.ajouter();
    setInterval(rafraichir, 1000);
  }
  await rafraichir();
}

preparerRejeu();
demarrer();
