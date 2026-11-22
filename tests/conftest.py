"""Outils communs : compile le projet, lance le vrai serveur Java, fournit des clients.

Seuls la bibliothèque standard Python et un JDK (javac + java) sont nécessaires.
"""

import json
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java"
DOCS = ROOT / "docs"


def port_libre():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def compiler(dest):
    javac = shutil.which("javac")
    if not javac:
        pytest.skip("javac introuvable : installez un JDK pour les tests d'intégration")
    sources = [str(p) for p in SRC.rglob("*.java")]
    subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-d", str(dest), *sources],
                   check=True)


@pytest.fixture(scope="session")
def classes(tmp_path_factory):
    dest = tmp_path_factory.mktemp("classes")
    compiler(dest)
    return dest


class Serveur:
    def __init__(self, classes, max_clients=50, web=True):
        self.port = port_libre()
        self.port_web = port_libre()
        cmd = ["java", "-Dfile.encoding=UTF-8", "-cp", str(classes), "chat.server.Serveur",
               "--port", str(self.port), "--web", str(self.port_web),
               "--docs", str(DOCS), "--max", str(max_clients)]
        if not web:
            cmd.append("--sans-web")
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding="utf-8")
        self.sortie = []
        threading.Thread(target=self._lire, daemon=True).start()
        limite = time.time() + 15
        while time.time() < limite:
            try:
                socket.create_connection(("127.0.0.1", self.port), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)
        else:
            self.arreter()
            raise RuntimeError("le serveur n'a pas démarré :\n" + "".join(self.sortie))
        # la connexion de sonde ci-dessus est vue comme un client qui part aussitôt
        time.sleep(0.1)

    def _lire(self):
        for ligne in self.proc.stdout:
            self.sortie.append(ligne)

    def url(self, chemin):
        return f"http://127.0.0.1:{self.port_web}{chemin}"

    def arreter(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()


@pytest.fixture
def serveur(classes):
    s = Serveur(classes)
    yield s
    s.arreter()


class ClientTCP:
    """Client brut : parle directement le protocole ligne par ligne."""

    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.tampon = b""
        self.ferme = False

    def envoyer(self, texte):
        self.sock.sendall((texte + "\n").encode("utf-8"))

    def lire(self, timeout=5):
        """Une ligne sans le « \n » ; None si le serveur a fermé ; socket.timeout sinon."""
        limite = time.time() + timeout
        while b"\n" not in self.tampon:
            if self.ferme:
                return None
            reste = limite - time.time()
            if reste <= 0:
                raise socket.timeout("rien reçu")
            self.sock.settimeout(reste)
            try:
                morceau = self.sock.recv(65536)
            except ConnectionResetError:
                morceau = b""
            if not morceau:
                self.ferme = True
                if not self.tampon:
                    return None
                self.tampon += b"\n"
            self.tampon += morceau
        ligne, self.tampon = self.tampon.split(b"\n", 1)
        return ligne.decode("utf-8")

    def lire_jusqua(self, prefixe, timeout=5):
        """Lit jusqu'à une ligne qui commence par prefixe ; renvoie toutes les lignes lues."""
        lues = []
        limite = time.time() + timeout
        while time.time() < limite:
            try:
                ligne = self.lire(max(0.05, limite - time.time()))
            except socket.timeout:
                break
            if ligne is None:
                break
            lues.append(ligne)
            if ligne.startswith(prefixe):
                return lues
        raise AssertionError(f"{prefixe!r} non reçu, lu : {lues}")

    def vider(self, attente=0.3):
        """Toutes les lignes arrivées pendant `attente` secondes."""
        lues = []
        while True:
            try:
                ligne = self.lire(attente)
            except socket.timeout:
                return lues
            if ligne is None:
                return lues
            lues.append(ligne)

    def connecter(self, pseudo):
        assert self.lire() == "PSEUDO"
        self.envoyer(pseudo)
        return self.lire_jusqua("USERS|")

    def fermer(self):
        try:
            self.sock.close()
        except OSError:
            pass


@pytest.fixture
def client(serveur):
    crees = []

    def fabrique():
        c = ClientTCP(serveur.port)
        crees.append(c)
        return c

    yield fabrique
    for c in crees:
        c.fermer()


def post(url, **donnees):
    corps = urllib.parse.urlencode(donnees).encode("utf-8")
    req = urllib.request.Request(url, data=corps, method="POST")
    with urllib.request.urlopen(req, timeout=5) as r:
        brut = r.read().decode("utf-8")
        return r.status, (json.loads(brut) if brut else None)


def get_json(url):
    with urllib.request.urlopen(url, timeout=5) as r:
        return json.loads(r.read().decode("utf-8"))


class FluxSSE:
    """Lit le flux Server-Sent Events d'un participant web dans un thread."""

    def __init__(self, url):
        self.lignes = []
        self.fini = threading.Event()
        self.reponse = urllib.request.urlopen(url, timeout=30)
        threading.Thread(target=self._lire, daemon=True).start()

    def _lire(self):
        try:
            for brut in self.reponse:
                ligne = brut.decode("utf-8").rstrip("\n")
                if ligne.startswith("data: "):
                    self.lignes.append(ligne[6:])
        except Exception:
            pass
        finally:
            self.fini.set()

    def attendre(self, prefixe, timeout=5):
        limite = time.time() + timeout
        while time.time() < limite:
            for l in self.lignes:
                if l.startswith(prefixe):
                    return l
            time.sleep(0.05)
        raise AssertionError(f"{prefixe!r} non reçu par le flux web, lu : {self.lignes}")

    def fermer(self):
        try:
            self.reponse.close()
        except Exception:
            pass

