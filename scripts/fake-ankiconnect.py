#!/usr/bin/env python3
"""A stand-in for AnkiConnect with a small Japanese deck, for trying Hiroi's Anki
check and marks in the emulator (scripts/emulator.sh fake-anki). Answers deckNames,
findNotes, notesInfo and notesModTime on 127.0.0.1:8765.

Words with notes: 食べる, 届く, 友達, 教える (in HTML) and 使う (with furigana),
all in the deck "Japanese::Core" with the fields Word and Meaning. Their cards are
mature (食べる, 友達), learning (届く, 教える) or new (使う), so the default mark
rules give them different colours.

Searches: AND, OR and parentheses over deck:, Field:*text*, is:new, is:due (none),
is:suspended (none), prop:ivl / prop:lapses comparisons, each optionally negated
with -. Anything else is an invalid search, as Anki would report it.
"""
import json
import operator
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

# id: (deck, fields, interval in days (0 = new), lapses)
NOTES = {
    1: ("Japanese::Core", {"Word": "食べる", "Meaning": "to eat"}, 40, 0),
    2: ("Japanese::Core", {"Word": "届く", "Meaning": "to reach"}, 3, 1),
    3: ("Japanese::Core", {"Word": "友達", "Meaning": "friend"}, 90, 0),
    4: ("Japanese::Core", {"Word": "<b>教える</b>", "Meaning": "to teach"}, 7, 9),
    5: ("Japanese::Core", {"Word": "使う[つかう]", "Meaning": "to use"}, 0, 0),
    6: ("Default", {"Front": "設定", "Back": "setting"}, 0, 0),
}

COMPARE = {">=": operator.ge, "<=": operator.le, ">": operator.gt, "<": operator.lt, "=": operator.eq, "!=": operator.ne}


class SearchError(Exception):
    pass


def unescape(text):
    return re.sub(r"\\(.)", r"\1", text)


def split(query, separator):
    """Splits at `separator` outside parentheses and quotes."""
    parts, current, depth, quoted, i = [], "", 0, False, 0
    while i < len(query):
        c = query[i]
        if c == "\\":
            current += query[i:i + 2]
            i += 2
            continue
        if c == '"':
            quoted = not quoted
        elif not quoted and c == "(":
            depth += 1
        elif not quoted and c == ")":
            depth -= 1
        if not quoted and depth == 0 and query.startswith(separator, i):
            parts.append(current)
            current = ""
            i += len(separator)
            continue
        current += c
        i += 1
    parts.append(current)
    return [p.strip() for p in parts if p.strip()]


def matches(note, query):
    alternatives = split(query, " OR ")
    if len(alternatives) > 1:
        return any(matches(note, a) for a in alternatives)
    terms = split(query, " ")
    if len(terms) > 1:
        return all(matches(note, t) for t in terms)
    if not terms:
        return True
    term = terms[0]
    if term.startswith("(") and term.endswith(")"):
        return matches(note, term[1:-1])
    negated = term.startswith("-")
    if negated:
        term = term[1:]
    if term.startswith('"') and term.endswith('"'):
        term = term[1:-1]
    return term_matches(note, term) != negated


def term_matches(note, term):
    deck, fields, ivl, lapses = note
    if term.startswith("deck:"):
        name = unescape(term[5:])
        return deck == name or deck.startswith(name + "::")
    if term == "is:new":
        return ivl == 0
    if term in ("is:due", "is:suspended"):
        return False
    prop = re.fullmatch(r"prop:(ivl|lapses)(>=|<=|!=|>|<|=)(\d+)", term)
    if prop:
        value = ivl if prop.group(1) == "ivl" else lapses
        return COMPARE[prop.group(2)](value, int(prop.group(3)))
    field = re.fullmatch(r"((?:[^:\\]|\\.)+):\*((?:[^\\]|\\.)*)\*", term)
    if field:
        return unescape(field.group(2)) in fields.get(unescape(field.group(1)), "")
    raise SearchError(f"Invalid search: {term}")


def find_notes(query):
    return [i for i, note in NOTES.items() if matches(note, query)]


def notes_info(ids):
    return [
        {"noteId": i, "modelName": "Basic", "tags": [], "mod": 1,
         "fields": {name: {"value": value, "order": order} for order, (name, value) in enumerate(NOTES[i][1].items())}}
        for i in ids if i in NOTES
    ]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        params = request.get("params", {})
        actions = {
            "version": lambda: 6,
            "deckNames": lambda: sorted({note[0] for note in NOTES.values()}),
            "findNotes": lambda: find_notes(params["query"]),
            "notesInfo": lambda: notes_info(params["notes"]),
            "notesModTime": lambda: [{"noteId": i, "mod": 1} for i in params["notes"] if i in NOTES],
        }
        action = actions.get(request.get("action"))
        try:
            reply = {"result": action(), "error": None} if action else {"result": None, "error": "unsupported action"}
        except SearchError as e:
            reply = {"result": None, "error": str(e)}
        body = json.dumps(reply, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print("fake-ankiconnect:", format % args, flush=True)


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
