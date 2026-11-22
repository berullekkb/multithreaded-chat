"""Tests d'intégration : lancent le vrai serveur Java et lui parlent par TCP et HTTP."""

import socket
import threading
import time
import urllib.error
import urllib.request

import pytest

from conftest import ClientTCP, FluxSSE, Serveur, get_json, post


def corps(ligne):
    """Retire l'heure (2e champ) des lignes horodatées pour comparer le reste."""
    champs = ligne.split("|")
    if champs[0] in ("MSG", "HISTO", "PRIVE", "INFO"):
        champs[1] = "hh:mm:ss"
    return "|".join(champs)


# ---------------------------------------------------------------- pseudonyme

def test_connexion_et_bienvenue(client):
    jack = client()
    lignes = [corps(l) for l in jack.connecter("Jack")]
    assert lignes == [
        "OK|Jack",
        "INFO|hh:mm:ss|Bienvenue Jack ! Tapez /help pour voir les commandes.",
        "INFO|hh:mm:ss|Jack a rejoint la conversation.",
        "USERS|Jack",
    ]


def test_pseudo_deja_pris_insensible_a_la_casse(client):
    client().connecter("Jack")
    sarah = client()
    assert sarah.lire() == "PSEUDO"
    sarah.envoyer("jACK")
    assert sarah.lire() == "ERREUR|Ce pseudonyme est déjà utilisé."
    assert sarah.lire() == "PSEUDO"
    sarah.envoyer("Sarah")
    assert sarah.lire() == "OK|Sarah"


@pytest.mark.parametrize("pseudo, erreur", [
    ("", "ERREUR|Le pseudo ne peut pas être vide."),
    ("   ", "ERREUR|Le pseudo ne peut pas être vide."),
    ("a b", "ERREUR|Pseudo invalide : 1 à 20 caractères, lettres, chiffres, _ ou -."),
    ("x" * 21, "ERREUR|Pseudo invalide : 1 à 20 caractères, lettres, chiffres, _ ou -."),
    ("pipe|pipe", "ERREUR|Pseudo invalide : 1 à 20 caractères, lettres, chiffres, _ ou -."),
])
def test_pseudo_invalide(client, pseudo, erreur):
    c = client()
    assert c.lire() == "PSEUDO"
    c.envoyer(pseudo)
    assert c.lire() == erreur
    assert c.lire() == "PSEUDO"


def test_pseudo_accentue_accepte(client):
    c = client()
    assert c.connecter("Héloïse_2")[0] == "OK|Héloïse_2"


def test_exit_pendant_le_choix_du_pseudo(client):
    c = client()
    assert c.lire() == "PSEUDO"
    c.envoyer("exit")
    assert c.lire() == "BYE"
    assert c.lire() is None


# ---------------------------------------------------------------- messages

def test_diffusion_a_tous_et_utf8(client):
    jack, sarah = client(), client()
    jack.connecter("Jack")
    sarah.connecter("Sarah")
    jack.vider()
    jack.envoyer("Bonjour à tous, ça va ? | €")
    attendu = "MSG|hh:mm:ss|Jack|Bonjour à tous, ça va ? | €"
    assert corps(jack.lire_jusqua("MSG|")[-1]) == attendu
    assert corps(sarah.lire_jusqua("MSG|")[-1]) == attendu


def test_historique_pour_les_nouveaux(client):
    jack = client()
    jack.connecter("Jack")
    for i in range(25):
        jack.envoyer(f"message {i}")
    jack.lire_jusqua("MSG|", 5)
    time.sleep(0.3)
    youcef = client()
    lignes = youcef.connecter("Youcef")
    histo = [l for l in lignes if l.startswith("HISTO|")]
    # seuls les 20 derniers messages sont gardés, dans l'ordre
    assert len(histo) == 20
    assert histo[0].endswith("|Jack|message 5")
    assert histo[-1].endswith("|Jack|message 24")
    assert lignes[0] == "OK|Youcef"


def test_message_vide_ignore_et_texte_coupe(client):
    jack = client()
    jack.connecter("Jack")
    jack.envoyer("    ")
    jack.envoyer("y" * 800)
    msg = jack.lire_jusqua("MSG|")[-1]
    assert msg.split("|", 3)[3] == "y" * 500


def test_commande_list_et_help(client):
    jack, sarah = client(), client()
    jack.connecter("Jack")
    sarah.connecter("sarah")
    jack.vider()
    jack.envoyer("/list")
    assert corps(jack.lire()) == "INFO|hh:mm:ss|Connectés (2) : Jack, sarah"
    jack.envoyer("/help")
    assert jack.lire().endswith("/quit ou exit (quitter).")
    jack.envoyer("/bidule")
    assert jack.lire() == "ERREUR|Commande inconnue : /bidule (tapez /help)"


def test_message_prive(client):
    jack, sarah, youcef = client(), client(), client()
    jack.connecter("Jack")
    sarah.connecter("Sarah")
    youcef.connecter("Youcef")
    for c in (jack, sarah, youcef):
        c.vider()
    sarah.envoyer("/msg jack Tu as fini le TD ?")
    attendu = "PRIVE|hh:mm:ss|Sarah|Jack|Tu as fini le TD ?"
    assert corps(jack.lire()) == attendu
    assert corps(sarah.lire()) == attendu
    assert youcef.vider() == []

    sarah.envoyer("/msg Personne coucou")
    assert sarah.lire() == "ERREUR|Utilisateur introuvable : Personne"
    sarah.envoyer("/msg Sarah moi")
    assert sarah.lire() == "ERREUR|Vous ne pouvez pas vous écrire à vous-même."
    sarah.envoyer("/msg Jack")
    assert sarah.lire() == "ERREUR|Usage : /msg <pseudo> <texte>"


# ---------------------------------------------------------------- départs

def test_depart_volontaire(client):
    jack, sarah = client(), client()
    jack.connecter("Jack")
    sarah.connecter("Sarah")
    jack.vider()
    sarah.envoyer("exit")
    assert sarah.lire() == "BYE"
    assert sarah.lire() is None
    lignes = [corps(l) for l in jack.lire_jusqua("USERS|")]
    assert lignes == ["INFO|hh:mm:ss|Sarah a quitté la conversation.", "USERS|Jack"]
    # le pseudonyme est libéré
    autre = client()
    assert autre.connecter("Sarah")[0] == "OK|Sarah"


def test_depart_brutal(client):
    jack, sarah = client(), client()
    jack.connecter("Jack")
    sarah.connecter("Sarah")
    jack.vider()
    sarah.sock.close()
    lignes = [corps(l) for l in jack.lire_jusqua("USERS|")]
    assert lignes == ["INFO|hh:mm:ss|Sarah a quitté la conversation.", "USERS|Jack"]


# ---------------------------------------------------------------- concurrence

def test_trente_clients_simultanes_meme_ordre(client):
    """30 clients envoient en même temps : chacun reçoit les 30 messages, dans le même ordre."""
    n = 30
    clients = [client() for _ in range(n)]
    for i, c in enumerate(clients):
        c.connecter(f"c{i}")
    time.sleep(0.5)
    for c in clients:
        c.vider(0.2)

    depart = threading.Barrier(n)

    def parler(i):
        depart.wait()
        clients[i].envoyer(f"bonjour de c{i}")

    threads = [threading.Thread(target=parler, args=(i,)) for i in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    recus = []
    for c in clients:
        msgs = []
        limite = time.time() + 10
        while len(msgs) < n and time.time() < limite:
            ligne = c.lire(5)
            if ligne and ligne.startswith("MSG|"):
                msgs.append(ligne.split("|", 2)[2])
        recus.append(msgs)
    assert all(len(m) == n for m in recus)
    assert sorted(recus[0]) == sorted(f"c{i}|bonjour de c{i}" for i in range(n))
    # le verrou de diffusion garantit un ordre identique pour tout le monde
    assert all(m == recus[0] for m in recus)


def test_reservation_atomique_du_pseudo(serveur):
    """20 clients demandent le même pseudo au même moment : un seul l'obtient."""
    n = 20
    clients = [ClientTCP(serveur.port) for _ in range(n)]
    for c in clients:
        assert c.lire() == "PSEUDO"
    depart = threading.Barrier(n)
    reponses = [None] * n

    def demander(i):
        depart.wait()
        clients[i].envoyer("Unique")
        reponses[i] = clients[i].lire()

    threads = [threading.Thread(target=demander, args=(i,)) for i in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert reponses.count("OK|Unique") == 1
    assert reponses.count("ERREUR|Ce pseudonyme est déjà utilisé.") == n - 1
    for c in clients:
        c.fermer()


def test_serveur_complet(classes):
    s = Serveur(classes, max_clients=2, web=False)
    try:
        a, b = ClientTCP(s.port), ClientTCP(s.port)
        a.connecter("A")
        b.connecter("B")
        c = ClientTCP(s.port)
        lignes = []
        while True:
            l = c.lire()
            if l is None:
                break
            lignes.append(l)
            if l == "PSEUDO":
                c.envoyer("C")
        assert "ERREUR|Serveur complet, réessayez plus tard." in lignes
        assert lignes[-1] == "BYE"
    finally:
        s.arreter()


# ---------------------------------------------------------------- passerelle web

def test_interface_web_servie(serveur):
    with urllib.request.urlopen(serveur.url("/"), timeout=5) as r:
        assert r.status == 200
        assert "text/html" in r.headers["Content-Type"]
        assert "<title>" in r.read().decode("utf-8")
    with urllib.request.urlopen(serveur.url("/assets/app.js"), timeout=5) as r:
        assert "javascript" in r.headers["Content-Type"]


@pytest.mark.parametrize("chemin", ["/../README.md", "/%2e%2e/README.md", "/assets/../../pom.xml",
                                    "/inexistant.html"])
def test_pas_de_sortie_du_dossier_docs(serveur, chemin):
    with pytest.raises(urllib.error.HTTPError) as e:
        urllib.request.urlopen(serveur.url(chemin), timeout=5)
    assert e.value.code == 404


def test_status(serveur, client):
    client().connecter("Jack")
    st = get_json(serveur.url("/api/status"))
    assert st["mode"] == "live"
    assert st["port_tcp"] == serveur.port
    assert [p["pseudo"] for p in st["participants"]] == ["Jack"]
    assert st["participants"][0]["type"] == "tcp"
    assert st["participants"][0]["thread"].startswith("client-tcp-")


def test_web_et_tcp_dans_le_meme_salon(serveur, client):
    jack = client()
    jack.connecter("Jack")
    jack.vider()

    # pseudo déjà pris par un client TCP
    code, r = post(serveur.url("/api/join"), pseudo="jack")
    assert r == {"ok": False, "erreur": "Ce pseudonyme est déjà utilisé."}

    code, r = post(serveur.url("/api/join"), pseudo="Amina")
    assert r["ok"] and r["pseudo"] == "Amina"
    flux = FluxSSE(serveur.url("/api/flux?jeton=" + r["jeton"]))
    try:
        assert flux.attendre("OK|") == "OK|Amina"
        assert flux.attendre("USERS|") == "USERS|Amina,Jack"
        assert corps(jack.lire_jusqua("INFO|")[-1]) == "INFO|hh:mm:ss|Amina a rejoint la conversation."

        # web -> TCP
        post(serveur.url("/api/send"), jeton=r["jeton"], texte="Salut depuis le navigateur é")
        assert corps(jack.lire_jusqua("MSG|")[-1]) == "MSG|hh:mm:ss|Amina|Salut depuis le navigateur é"

        # TCP -> web, y compris en privé
        jack.envoyer("/msg Amina secret")
        assert corps(flux.attendre("PRIVE|")) == "PRIVE|hh:mm:ss|Jack|Amina|secret"

        st = get_json(serveur.url("/api/status"))
        web = [p for p in st["participants"] if p["type"] == "web"][0]
        assert web["pseudo"] == "Amina" and web["thread"].startswith("web-")

        # départ par /quit
        post(serveur.url("/api/send"), jeton=r["jeton"], texte="/quit")
        assert flux.attendre("BYE") == "BYE"
        assert corps(jack.lire_jusqua("USERS|")[-1]) == "USERS|Jack"
        assert flux.fini.wait(5)
    finally:
        flux.fermer()


def test_web_leave_et_session_inconnue(serveur):
    _, r = post(serveur.url("/api/join"), pseudo="Lucas")
    post(serveur.url("/api/leave"), jeton=r["jeton"])
    with pytest.raises(urllib.error.HTTPError) as e:
        post(serveur.url("/api/send"), jeton=r["jeton"], texte="encore là ?")
    assert e.value.code == 410
    with pytest.raises(urllib.error.HTTPError) as e:
        urllib.request.urlopen(serveur.url("/api/flux?jeton=faux"), timeout=5)
    assert e.value.code == 404
    assert get_json(serveur.url("/api/status"))["participants"] == []


def test_arret_du_serveur_previent_les_clients(classes):
    s = Serveur(classes, web=False)
    try:
        jack = ClientTCP(s.port)
        jack.connecter("Jack")
        jack.vider()
        s.proc.terminate()  # Ctrl+C : le « shutdown hook » prévient tout le monde
        lignes = [corps(l) for l in jack.lire_jusqua("BYE")]
        assert lignes == ["INFO|hh:mm:ss|Le serveur s'arrête. À bientôt !", "BYE"]
    finally:
        s.arreter()
