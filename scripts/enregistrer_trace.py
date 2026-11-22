"""Enregistre une vraie session sur le serveur Java et l'écrit dans docs/data/trace.json.

Le site la rejoue (section « Protocole ») et tests/js/parite.test.mjs vérifie que
le serveur simulé de la démo produit exactement les mêmes lignes.

Usage (depuis la racine du projet) :
    python scripts/enregistrer_trace.py
"""

import json
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SORTIE = ROOT / "docs" / "data" / "trace.json"

# (client, action, texte, légende) — action = "connexion" ou "envoi"
SCENARIO = [
    ("Jack", "connexion", None,
     "Jack ouvre un socket : le serveur lui confie un thread et demande un pseudo."),
    ("Jack", "envoi", "Jack",
     "Pseudo accepté : Jack reçoit la bienvenue, puis l'annonce et la liste des connectés."),
    ("Sarah", "connexion", None,
     "Sarah se connecte à son tour ; son thread travaille en parallèle de celui de Jack."),
    ("Sarah", "envoi", "jack",
     "« jack » est refusé : l'unicité des pseudos ne tient pas compte de la casse."),
    ("Sarah", "envoi", "Sarah",
     "Sarah entre dans le salon : tout le monde reçoit l'annonce et la nouvelle liste."),
    ("Jack", "envoi", "Bonjour tout le monde",
     "Un message public est diffusé à tous, expéditeur compris, avec l'heure du serveur."),
    ("Sarah", "envoi", "/msg Jack Tu as fini le TD ?",
     "Un message privé ne part que vers le destinataire et l'expéditeur."),
    ("Youcef", "connexion", None,
     "Youcef arrive en retard…"),
    ("Youcef", "envoi", "Youcef",
     "… et reçoit les derniers messages publics (HISTO) avant d'être annoncé."),
    ("Youcef", "envoi", "/list",
     "/list ne répond qu'à celui qui le demande."),
    ("Sarah", "envoi", "exit",
     "Sarah quitte : BYE ferme sa connexion, les autres sont prévenus et la liste est mise à jour."),
]


class Client:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.tampon = b""
        self.ferme = False

    def envoyer(self, texte):
        self.sock.sendall((texte + "\n").encode("utf-8"))

    def recues(self, silence=0.25):
        """Toutes les lignes arrivées jusqu'à `silence` secondes sans rien recevoir."""
        while not self.ferme:
            self.sock.settimeout(silence)
            try:
                morceau = self.sock.recv(65536)
            except socket.timeout:
                break
            if not morceau:
                self.ferme = True
                break
            self.tampon += morceau
        *lignes, self.tampon = self.tampon.split(b"\n")
        return [l.decode("utf-8") for l in lignes]


def enregistrer(port):
    clients = {}
    etapes = []
    for nom, action, texte, legende in SCENARIO:
        if action == "connexion":
            clients[nom] = Client(port)
        else:
            clients[nom].envoyer(texte)
        time.sleep(0.15)
        recus = {n: c.recues() for n, c in clients.items()}
        etapes.append({
            "client": nom,
            "action": action,
            "texte": texte,
            "legende": legende,
            "recus": {n: l for n, l in recus.items() if l},
        })
    for c in clients.values():
        c.sock.close()
    return {
        "source": "serveur Java réel, scripts/enregistrer_trace.py",
        "enregistre_le": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "clients": list(clients),
        "etapes": etapes,
    }


def port_libre():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    javac = shutil.which("javac")
    if not javac:
        sys.exit("javac introuvable : installez un JDK.")
    with tempfile.TemporaryDirectory() as classes:
        sources = [str(p) for p in (ROOT / "src").rglob("*.java")]
        subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-d", classes, *sources],
                       check=True)
        port = port_libre()
        proc = subprocess.Popen(["java", "-cp", classes, "chat.server.Serveur",
                                 "--port", str(port), "--sans-web"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(150):
                try:
                    socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                    break
                except OSError:
                    time.sleep(0.1)
            time.sleep(0.2)
            trace = enregistrer(port)
        finally:
            proc.terminate()
            proc.wait(5)
    SORTIE.parent.mkdir(parents=True, exist_ok=True)
    SORTIE.write_text(json.dumps(trace, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(trace['etapes'])} étapes écrites dans {SORTIE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
