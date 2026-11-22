"""La trace rejouée sur le site doit correspondre au serveur Java actuel."""

import json
import sys
from pathlib import Path

from conftest import Serveur, ROOT

sys.path.insert(0, str(ROOT / "scripts"))
from enregistrer_trace import enregistrer  # noqa: E402

from test_serveur import corps  # noqa: E402


def sans_heure(trace):
    return [{nom: [corps(l) for l in lignes] for nom, lignes in e["recus"].items()}
            for e in trace["etapes"]]


def test_trace_a_jour(classes):
    s = Serveur(classes, web=False)
    try:
        neuve = enregistrer(s.port)
    finally:
        s.arreter()
    publiee = json.loads((ROOT / "docs" / "data" / "trace.json").read_text(encoding="utf-8"))
    assert sans_heure(neuve) == sans_heure(publiee), \
        "docs/data/trace.json est périmée : relancez python scripts/enregistrer_trace.py"
