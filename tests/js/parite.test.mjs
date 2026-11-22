// Parité : le serveur simulé de la démo (docs/assets/simulateur.js) doit produire
// exactement les mêmes lignes que le vrai serveur Java, enregistrées dans
// docs/data/trace.json par scripts/enregistrer_trace.py. Seule l'heure est ignorée.
//
// Lancer : node tests/js/parite.test.mjs

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { ServeurSimule } from "../../docs/assets/simulateur.js";
import { sansHeure, nettoyer, decouper, analyser } from "../../docs/assets/protocole.js";

const racine = new URL("../../", import.meta.url);
const trace = JSON.parse(readFileSync(fileURLToPath(new URL("docs/data/trace.json", racine)), "utf-8"));

let echecs = 0;
function test(nom, f) {
  try {
    f();
    console.log(`ok   ${nom}`);
  } catch (e) {
    echecs++;
    console.log(`ÉCHEC ${nom}\n${e.message}`);
  }
}

test("la trace contient le scénario complet", () => {
  assert.equal(trace.etapes.length, 11);
  assert.deepEqual(trace.clients, ["Jack", "Sarah", "Youcef"]);
});

test("le simulateur rejoue la trace Java ligne pour ligne", () => {
  const sim = new ServeurSimule();
  const boites = {};
  const connexions = {};
  trace.etapes.forEach((etape, i) => {
    if (etape.action === "connexion") {
      boites[etape.client] = [];
      connexions[etape.client] = sim.connecter((l) => boites[etape.client].push(l));
    } else {
      connexions[etape.client].envoyer(etape.texte);
    }
    const obtenu = {};
    for (const [nom, lignes] of Object.entries(boites)) {
      if (lignes.length) obtenu[nom] = lignes.splice(0).map(sansHeure);
    }
    const attendu = Object.fromEntries(
      Object.entries(etape.recus).map(([nom, lignes]) => [nom, lignes.map(sansHeure)]));
    assert.deepEqual(obtenu, attendu, `étape ${i + 1} (${etape.client} : ${etape.texte ?? "connexion"})`);
  });
});

test("cas limites identiques à Java : nettoyage et découpage", () => {
  assert.equal(nettoyer("  a\tb\u0000c  "), "a b c");
  assert.equal(nettoyer("y".repeat(800)).length, 500);
  assert.deepEqual(decouper("MSG|12:00:00|Jack|a | b", 4), ["MSG", "12:00:00", "Jack", "a | b"]);
  assert.deepEqual(analyser("USERS|"), { type: "USERS", pseudos: [] });
});

test("serveur simulé : pseudo pris, privé, départ brutal", () => {
  const sim = new ServeurSimule();
  const a = [], b = [];
  const ca = sim.connecter((l) => a.push(l));
  ca.envoyer("Jack");
  const cb = sim.connecter((l) => b.push(l));
  cb.envoyer("JACK");
  assert.equal(b.at(-2), "ERREUR|Ce pseudonyme est déjà utilisé.");
  cb.envoyer("Amina");
  cb.envoyer("/msg jack coucou");
  assert.equal(sansHeure(a.at(-1)), "PRIVE|hh:mm:ss|Amina|Jack|coucou");
  cb.fermer();
  assert.equal(a.at(-1), "USERS|Jack");
  assert.equal(sim.status().participants.length, 1);
});

if (echecs) {
  console.log(`\n${echecs} test(s) en échec`);
  process.exit(1);
}
console.log("\nparité navigateur / Java : OK");
