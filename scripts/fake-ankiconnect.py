#!/usr/bin/env python3
"""A stand-in for AnkiConnect with a small Japanese deck, for trying Hiroi's Anki
check in the emulator (scripts/emulator.sh fake-anki). Answers deckNames,
findNotes and notesInfo, the actions Hiroi uses, on 127.0.0.1:8765.

Words with notes: 食べる, 届く, 友達, 教える (in HTML) and 使う (with furigana),
all in the deck "Japanese::Core" with the fields Word and Meaning.
"""
import json
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

NOTES = {
    1: ("Japanese::Core", {"Word": "食べる", "Meaning": "to eat"}),
    2: ("Japanese::Core", {"Word": "届く", "Meaning": "to reach"}),
    3: ("Japanese::Core", {"Word": "友達", "Meaning": "friend"}),
    4: ("Japanese::Core", {"Word": "<b>教える</b>", "Meaning": "to teach"}),
    5: ("Japanese::Core", {"Word": "使う[つかう]", "Meaning": "to use"}),
    6: ("Default", {"Front": "設定", "Back": "setting"}),
}


def unescape(text):
    return re.sub(r"\\(.)", r"\1", text)


def find_notes(query):
    """Understands the queries Hiroi sends: "deck:X" and ("Field:*word*" OR ...)."""
    deck = re.search(r'"deck:((?:[^"\\]|\\.)*)"', query)
    terms = re.findall(r'"((?:[^":\\]|\\.)+):\*((?:[^"\\]|\\.)*)\*"', query)
    found = []
    for note_id, (note_deck, fields) in NOTES.items():
        if deck and not (note_deck == unescape(deck.group(1)) or note_deck.startswith(unescape(deck.group(1)) + "::")):
            continue
        if terms and not any(unescape(value) in fields.get(unescape(field), "") for field, value in terms):
            continue
        found.append(note_id)
    return found


def notes_info(ids):
    return [
        {"noteId": i, "modelName": "Basic", "tags": [],
         "fields": {name: {"value": value, "order": order} for order, (name, value) in enumerate(NOTES[i][1].items())}}
        for i in ids if i in NOTES
    ]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        params = request.get("params", {})
        actions = {
            "version": lambda: 6,
            "deckNames": lambda: sorted({deck for deck, _ in NOTES.values()}),
            "findNotes": lambda: find_notes(params["query"]),
            "notesInfo": lambda: notes_info(params["notes"]),
        }
        action = actions.get(request.get("action"))
        reply = {"result": action(), "error": None} if action else {"result": None, "error": "unsupported action"}
        body = json.dumps(reply, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print("fake-ankiconnect:", format % args, flush=True)


HTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
