"""Captures d'écran du site pour le README (docs/images/*.png).

Lance le vrai serveur Java, y connecte deux navigateurs et un client TCP, puis
photographie l'interface. Une seconde passe photographie la démo (serveur simulé).

Prérequis : un JDK, puis  pip install playwright  et  python -m playwright install chromium
Usage (depuis la racine du projet) :  python scripts/capture_site.py
"""

import asyncio
import functools
import http.server
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
IMAGES = DOCS / "images"


def port_libre():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def attendre(port):
    for _ in range(150):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            return
        except OSError:
            time.sleep(0.1)
    raise RuntimeError(f"rien n'écoute sur le port {port}")


class Tcp:
    def __init__(self, port, pseudo):
        self.s = socket.create_connection(("127.0.0.1", port))
        self.s.recv(100)  # PSEUDO
        self.envoyer(pseudo)

    def envoyer(self, texte):
        self.s.sendall((texte + "\n").encode("utf-8"))


async def ecrire(page, texte, pause=600):
    await page.fill("#message", texte)
    await page.press("#message", "Enter")
    await page.wait_for_timeout(pause)


async def capturer(port_tcp, port_web, port_statique):
    IMAGES.mkdir(exist_ok=True)
    async with async_playwright() as p:
        nav = await p.chromium.launch()
        vue = {"width": 1360, "height": 900}

        # --- mode en direct : deux navigateurs et un terminal sur le vrai serveur
        a = await nav.new_page(viewport=vue)
        b = await nav.new_page(viewport=vue)
        await a.goto(f"http://localhost:{port_web}/")
        await b.goto(f"http://localhost:{port_web}/")
        await a.wait_for_timeout(800)
        await a.fill("#pseudo", "Berulle")
        await a.click("#formRejoindre button")
        await b.fill("#pseudo", "Sarah")
        await b.click("#formRejoindre button")
        await a.wait_for_timeout(500)
        jack = Tcp(port_tcp, "Jack")
        await a.wait_for_timeout(500)
        await ecrire(a, "Salut ! J'écris depuis le navigateur.")
        jack.envoyer("Et moi depuis un terminal : java -cp bin chat.client.Client")
        await a.wait_for_timeout(500)
        await ecrire(b, "Tout le monde reçoit les messages dans le même ordre ?")
        jack.envoyer("Oui, toutes les diffusions passent par le même verrou.")
        await a.wait_for_timeout(500)
        await ecrire(b, "/msg Berulle Tu me montres les threads côté serveur ?", 150)
        await a.locator(".poste").screenshot(path=str(IMAGES / "site_salon.png"))

        # --- mode démo : protocole rejoué
        d = await nav.new_page(viewport={"width": 1280, "height": 900})
        await d.goto(f"http://localhost:{port_statique}/")
        await d.wait_for_timeout(800)
        for _ in range(6):
            await d.click("#etapeSuivante")
        await d.locator(".rejeu").screenshot(path=str(IMAGES / "site_protocole.png"))
        await nav.close()


def main():
    javac = shutil.which("javac")
    if not javac:
        sys.exit("javac introuvable : installez un JDK.")
    with tempfile.TemporaryDirectory() as classes:
        sources = [str(f) for f in (ROOT / "src").rglob("*.java")]
        subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-d", classes, *sources], check=True)
        port_tcp, port_web, port_statique = port_libre(), port_libre(), port_libre()
        serveur = subprocess.Popen(
            ["java", "-cp", classes, "chat.server.Serveur", "--port", str(port_tcp),
             "--web", str(port_web), "--docs", str(DOCS)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        statique = http.server.ThreadingHTTPServer(
            ("127.0.0.1", port_statique),
            functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(DOCS)))
        threading.Thread(target=statique.serve_forever, daemon=True).start()
        try:
            attendre(port_web)
            asyncio.run(capturer(port_tcp, port_web, port_statique))
        finally:
            statique.shutdown()
            serveur.terminate()
            serveur.wait(5)
    print("Captures écrites dans", IMAGES.relative_to(ROOT))


if __name__ == "__main__":
    main()
