"""Cohérence du site docs/ (sans Java) : fichiers présents, trace valide, liens internes."""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"


def test_fichiers_du_site():
    for f in ["index.html", "assets/style.css", "assets/app.js", "assets/protocole.js",
              "assets/simulateur.js", "assets/robots.js", "data/trace.json", ".nojekyll"]:
        assert (DOCS / f).is_file(), f


def test_ressources_referencees_existent():
    html = (DOCS / "index.html").read_text(encoding="utf-8")
    css = (DOCS / "assets" / "style.css").read_text(encoding="utf-8")
    for ref in re.findall(r'(?:src|href)="((?:assets|data)/[^"#?]+)"', html):
        assert (DOCS / ref).is_file(), ref
    for ref in re.findall(r"url\((polices/[^)]+)\)", css):
        assert (DOCS / "assets" / ref).is_file(), ref


def test_ancres_de_navigation():
    html = (DOCS / "index.html").read_text(encoding="utf-8")
    ids = set(re.findall(r'id="([^"]+)"', html))
    for ancre in re.findall(r'href="#([^"]+)"', html):
        assert ancre in ids, ancre
    for ident in re.findall(r'\$\("([^"]+)"\)', (DOCS / "assets" / "app.js").read_text(encoding="utf-8")):
        assert ident in ids, f"app.js utilise #{ident} absent de index.html"


def test_trace_bien_formee():
    trace = json.loads((DOCS / "data" / "trace.json").read_text(encoding="utf-8"))
    assert trace["clients"] == ["Jack", "Sarah", "Youcef"]
    types = {"PSEUDO", "OK", "ERREUR", "MSG", "HISTO", "PRIVE", "INFO", "USERS", "BYE"}
    for etape in trace["etapes"]:
        assert etape["client"] in trace["clients"]
        assert etape["action"] in ("connexion", "envoi")
        assert etape["legende"]
        for lignes in etape["recus"].values():
            for l in lignes:
                assert l.split("|")[0] in types, l
