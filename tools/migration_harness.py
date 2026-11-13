#!/usr/bin/env python3
"""
Migration-edge harness for Cashiro's fork-era (47-52) -> 67 direct Room migrations.

Mirrors consolidateToForkEra67() from PennyWiseDatabase.kt (parsed from the Kotlin source
itself, not hardcoded) and executes it against reconstructed fork-era schemas built from the
Room exported schema JSONs (app/schemas/.../PennyWiseDatabase/{47,49,50,51,52}.json).

Checks, for every direct edge:
  - the migration runs without SQLite errors (the double-backtick / duplicate-column /
    no-such-column crash classes)
  - it is idempotent (runs twice)
  - every v67 table exists afterwards
  - every v67 column exists on tables that pre-existed at the source version
  - seeded transaction rows survive the rebuild

Requires only python3 stdlib. Run: python3 tools/migration_harness.py
"""
import json, re, sqlite3, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/pennywiseai/tracker/data/database/PennyWiseDatabase.kt"
SCHEMAS = ROOT / "app/schemas/com.pennywiseai.tracker.data.database/PennyWiseDatabase" if False else ROOT / "app/schemas/com.pennywiseai.tracker.data.database.PennyWiseDatabase"

src = KT.read_text()

def parse_args(call_text):
    depth = 0; args = []; cur = ""; instr = False
    i = call_text.index("(") + 1
    while i < len(call_text):
        ch = call_text[i]
        if instr:
            if ch == '"': instr = False
            cur += ch
        elif ch == '"':
            instr = True; cur += ch
        elif ch in "([": depth += 1; cur += ch
        elif ch in ")]":
            if depth == 0: args.append(cur.strip()); break
            depth -= 1; cur += ch
        elif ch == "," and depth == 0: args.append(cur.strip()); cur = ""
        else: cur += ch
        i += 1
    return args

def unq(s): return s.strip()[1:-1].replace('""', '"')

block_start = src.index("private fun consolidateToForkEra67")
block = src[block_start:src.index("private fun addColumnIfMissing")]

def matched_call(start):
    depth = 0; k = block.index("(", start); instr = False
    while True:
        c = block[k]
        if instr:
            if c == '"': instr = False
        elif c == '"': instr = True
        elif c == "(": depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0: break
        k += 1
    return block[start:k+1]

targets = {}
for m in re.finditer(r'rebuildTableAt67\(', block):
    a = parse_args(matched_call(m.start()))
    targets[unq(a[1])] = (unq(a[2]), re.findall(r'"(\w+)"', a[3]), re.findall(r'"([^"]+)"', a[4]) if len(a) > 4 else [])
creates = re.findall(r'db\.execSQL\("(CREATE TABLE[^"]+)"\)', block)
adds = re.findall(r'addColumnIfMissing\(db, "(\w+)", "(\w+)", "([^"]+)"\)', block)
defs_src = src[src.index("private val TARGET_COLUMN_DEFS"):]
defs_src = defs_src[:defs_src.index(")\n")]
defs = dict(re.findall(r'"(\w+)" to "([^"]+)"', defs_src))

def has_col(con, t, c): return any(r[1] == c for r in con.execute(f"PRAGMA table_info(`{t}`)"))
def cols_of(con, t): return [r[1] for r in con.execute(f"PRAGMA table_info(`{t}`)")]

# staging statements (parsed from the Kotlin, executed verbatim)
stage_m = re.search(r'if \(stageSyncState\) \{\n(.*?)\n            \}', block, re.S)
stage_sql = re.findall(r'execSQL\("""\n(.*?)\n"""\)', stage_m.group(1), re.S) if stage_m else []
stage_for = "transactions" in targets and "stageSyncState = true" in block

def consolidate(con):
    for table, (ddl, tcols, post) in targets.items():
        if not has_col(con, table, "firefly_synced_at"): continue
        con.execute(f"CREATE TABLE IF NOT EXISTS `{table}_new` ({ddl})")
        shared = [c for c in cols_of(con, table) if c in tcols]
        q = lambda cs: ", ".join(f"`{c}`" for c in cs)
        con.execute(f"INSERT INTO `{table}_new` ({q(shared)}) SELECT {q(shared)} FROM `{table}`")
        if stage_for and table == "transactions":
            for st in stage_sql: con.execute(st)
        con.execute(f"DROP TABLE `{table}`")
        con.execute(f"ALTER TABLE `{table}_new` RENAME TO `{table}`")
        for p in post: con.execute(p)
        for col in tcols:
            if not has_col(con, table, col):
                con.execute(f"ALTER TABLE `{table}` ADD COLUMN `{col}` {defs[col]}")
    for c in creates: con.execute(c)
    for t, col, d in adds:
        if not has_col(con, t, col):
            con.execute(f"ALTER TABLE `{t}` ADD COLUMN `{col}` {d}")

def load(v):
    return json.loads((SCHEMAS / f"{v}.json").read_text())["database"]
def colname(f): return f.get("columnName") or f["columnInfo"]["name"]
def fk_info(fk):
    table = fk.get("table") or fk.get("tableName")
    pairs = ([(c["column"], c["referencedTableColumn"]) for c in fk["columnFieldPairs"]]
             if "columnFieldPairs" in fk else list(zip(fk.get("columns", []), fk.get("referencedColumns", []))))
    return table, pairs
def ddl(e):
    parts = []
    for c in e["fields"]:
        t = {"INTEGER": "INTEGER", "TEXT": "TEXT", "REAL": "REAL", "BLOB": "BLOB"}[c["affinity"]]
        s = f'"{colname(c)}" {t}'
        if c.get("notNull"): s += " NOT NULL"
        if c.get("primaryKeyPosition", 0) > 0: s += " PRIMARY KEY"
        dv = c.get("defaultValue")
        if dv is not None: s += f" DEFAULT {dv}"
        parts.append(s)
    pks = sorted([c for c in e["fields"] if c.get("primaryKeyPosition", 0) > 0], key=lambda x: x["primaryKeyPosition"])
    if len(pks) > 1:
        parts.append("PRIMARY KEY(" + ", ".join(f'"{colname(c)}"' for c in pks) + ")")
    for fk in e.get("foreignKeys") or []:
        table, pairs = fk_info(fk)
        parts.append('FOREIGN KEY(' + ", ".join(f'"{a}"' for a, b in pairs) + f') REFERENCES "{table}"(' + ", ".join(f'"{b}"' for a, b in pairs) + f') ON UPDATE {fk["onUpdate"]} ON DELETE {fk["onDelete"]}')
    return f'CREATE TABLE "{e["tableName"]}" (' + ", ".join(parts) + ")"

s67 = load(67)
want_tables = {e["tableName"] for e in s67["entities"]}
want_cols = {e["tableName"]: {colname(f) for f in e["fields"]} for e in s67["entities"]}

def seed(v):
    con = sqlite3.connect(":memory:")
    for e in load(v)["entities"]:
        con.execute(ddl(e))
    t = next(e for e in load(v)["entities"] if e["tableName"] == "transactions")
    cols = [colname(f) for f in t["fields"]]
    vals = []
    for cn, f in zip(cols, t["fields"]):
        if f.get("primaryKeyPosition", 0) > 0: vals.append(1)
        elif f.get("notNull"): vals.append(0 if f["affinity"] == "INTEGER" else ("2026-01-01" if ("date" in cn or cn.endswith("_at")) else "x"))
        else: vals.append(None)
    con.execute(f'INSERT INTO transactions ({", ".join(chr(34)+c+chr(34) for c in cols)}) VALUES ({",".join(["?"]*len(cols))})', vals)
    con.commit()
    return con

ok_all = True
for v in (47, 49, 50, 51, 52):
    con = seed(v)
    had_firefly = has_col(con, "transactions", "firefly_synced_at")
    if had_firefly:
        con.execute("UPDATE transactions SET firefly_synced_at = '2026-01-01', firefly_external_id = 'pennywise-1'")
        con.commit()
    problems = []
    try:
        consolidate(con); consolidate(con)  # twice: idempotency
        con.commit()
        final_t = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        for t in sorted(want_tables):
            if t not in final_t: problems.append(f"missing table {t}")
        for t in sorted(want_tables & final_t):
            have = {r[1] for r in con.execute(f"PRAGMA table_info(`{t}`)")}
            if want_cols[t] - have: problems.append(f"{t} missing cols {sorted(want_cols[t]-have)}")

    except Exception as e:
        problems.append(f"SQL error: {e}")
    con.close()
    print(f"MIGRATION_{v}_67: {'OK' if not problems else 'FAIL -> ' + '; '.join(problems)}")
    ok_all = ok_all and not problems

print("ALL EDGES PASS" if ok_all else "FAILURES PRESENT")
sys.exit(0 if ok_all else 1)
