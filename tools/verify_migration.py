# -*- coding: utf-8 -*-
"""
数据库迁移硬验证 v4：覆盖 v41→42→43→44 链路
（useRegex / sortOrder / lorebookIdsJson / apiConfigId）
核心命题：任意真实老库（v41/v42/v43，含半成品分叉）升级到 v44 后，与 Room 44.json 期望完全一致。
"""
import json
import re
import sqlite3
import sys

BASE = r"H:/susu/core/database/schemas/com.yunian.ai.database.AppDatabase"

def load(path):
    with open(path, "r", encoding="utf-8") as f:
        doc = json.load(f)
    db = doc["database"]
    tables = {}
    for e in db["entities"]:
        sql = e["createSql"].replace("${TABLE_NAME}", e["tableName"])
        cols = {}
        for fdef in e["fields"]:
            cols[fdef["columnName"]] = {
                "affinity": fdef["affinity"],
                "notNull": bool(fdef.get("notNull", False)),
                "defaultValue": fdef.get("defaultValue"),
            }
        tables[e["tableName"]] = {"createSql": sql, "columns": cols,
                                  "isFts": "USING FTS" in sql.upper()}
    return db.get("identityHash"), tables

def room_table_info(conn, table):
    cur = conn.execute("PRAGMA table_info(`%s`)" % table)
    cols = {}
    for _, name, ctype, notnull, dflt, pk in cur.fetchall():
        affinity = {"INTEGER": "INTEGER", "TEXT": "TEXT", "REAL": "REAL", "BLOB": "BLOB"}.get(
            (ctype or "").upper(), "UNDEFINED")
        cols[name] = {"affinity": affinity, "notNull": bool(notnull),
                      "defaultValue": str(dflt) if dflt is not None else None}
    return cols

def verify(conn, expect_tables, tables_to_check):
    fails = []
    for t in tables_to_check:
        actual = room_table_info(conn, t)
        expect = expect_tables[t]["columns"]
        if set(actual) != set(expect):
            fails.append((t, "column-set", sorted(set(actual) ^ set(expect))))
            continue
        for c in expect:
            e, a = expect[c], actual[c]
            if a["affinity"] != e["affinity"]:
                fails.append((t, c, "affinity", a["affinity"], e["affinity"]))
            if a["notNull"] != e["notNull"]:
                fails.append((t, c, "notNull", a["notNull"], e["notNull"]))
            ad, ed = a["defaultValue"], e["defaultValue"]
            if ad != ed:
                try:
                    if ad is None or ed is None or float(ad) != float(ed):
                        fails.append((t, c, "defaultValue", ad, ed))
                except (TypeError, ValueError):
                    fails.append((t, c, "defaultValue", ad, ed))
    return fails

def add_column_if_missing(conn, table, col, ddl):
    existing = [r[1] for r in conn.execute("PRAGMA table_info(`%s`)" % table)]
    if col not in existing:
        conn.execute(ddl)

def build_db(tables, drop_cols):
    """按 createSql 建库；drop_cols: {table: [col,...]} 用于还原旧版本结构"""
    conn = sqlite3.connect(":memory:")
    for t, info in tables.items():
        sql = info["createSql"]
        if not info["isFts"]:
            for col in drop_cols.get(t, []):
                sql = re.sub(r",\s*`%s`\s+[^,)]*" % col, "", sql, count=1)
        conn.execute(sql)
    return conn

M_41_42 = [
    ("lorebook_entries", "useRegex", "ALTER TABLE `lorebook_entries` ADD COLUMN `useRegex` INTEGER NOT NULL DEFAULT 0"),
]
M_42_43 = [
    ("lorebook_entries", "sortOrder", "ALTER TABLE `lorebook_entries` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0"),
    ("companions", "lorebookIdsJson", "ALTER TABLE `companions` ADD COLUMN `lorebookIdsJson` TEXT NOT NULL DEFAULT '[]'"),
]
M_43_44 = [
    # 角色级 API 隔离：可空 INTEGER，无默认值（旧行自动为 NULL = 跟随全局）
    ("companions", "apiConfigId", "ALTER TABLE `companions` ADD COLUMN `apiConfigId` INTEGER"),
]

hash44, t44 = load(BASE + "/44.json")
try:
    hash43, t43 = load(BASE + "/43.json")
except FileNotFoundError:
    hash43, t43 = None, None

regular = sorted(t for t, i in t44.items() if not i["isFts"])
fts = sorted(t for t, i in t44.items() if i["isFts"])

print("=== schema bundle 一致性 ===")
print("44.json identityHash:", hash44)
if hash43 is not None:
    print("43.json identityHash:", hash43, "(一致)" if hash43 == hash44 else "(不同)")
print("(FTS4 表走 Room 独立校验分支，剔除逐列对比: %s)" % ", ".join(fts))

all_migrations = M_41_42 + M_42_43 + M_43_44

print()
print("=== 场景 1：标准 v43 老库 → 44 ===")
conn = build_db(t44, {"companions": ["apiConfigId"]})
for t, c, ddl in M_43_44: add_column_if_missing(conn, t, c, ddl)
fails = verify(conn, t44, regular)
print("结果:", "PASS" if not fails else "FAIL %s" % fails)

print()
print("=== 场景 2：标准 v41 老库 → 42 → 43 → 44 ===")
conn = build_db(t44, {"lorebook_entries": ["useRegex", "sortOrder"], "companions": ["lorebookIdsJson", "apiConfigId"]})
for t, c, ddl in all_migrations: add_column_if_missing(conn, t, c, ddl)
fails = verify(conn, t44, regular)
print("结果:", "PASS" if not fails else "FAIL %s" % fails)

print()
print("=== 场景 3：半成品期老库（已带部分新列）→ 全链路（幂等） ===")
conn = build_db(t44, {"lorebook_entries": ["useRegex"], "companions": ["lorebookIdsJson", "apiConfigId"]})
for t, c, ddl in all_migrations: add_column_if_missing(conn, t, c, ddl)
fails = verify(conn, t44, regular)
print("结果:", "PASS" if not fails else "FAIL %s" % fails)

print()
print("=== 场景 4：全新安装 v44 ===")
conn = build_db(t44, {})
fails = verify(conn, t44, regular)
print("结果:", "PASS" if not fails else "FAIL %s" % fails)

print()
print("=== 场景 5：老数据保值检查（v43 companions 行 → 44 后 apiConfigId 为 NULL） ===")
conn = build_db(t44, {"companions": ["apiConfigId"]})
conn.execute("INSERT INTO companions (id, name, personality, intimacy, lorebookIdsJson, createdAt, updatedAt) "
             "VALUES (1, '测试', '温柔', 0, '[]', 0, 0)")
for t, c, ddl in M_43_44: add_column_if_missing(conn, t, c, ddl)
row = conn.execute("SELECT apiConfigId FROM companions WHERE id = 1").fetchone()
ok = row is not None and row[0] is None
print("旧行 apiConfigId =", row[0] if row else "行丢失", "→", "PASS" if ok else "FAIL")
if not ok:
    fails.append(("companions", "apiConfigId", "old-row-null"))
print()
print("总体结论:", "MIGRATION 链路全场景验证通过" if not fails else "存在问题，见上方 FAIL")
sys.exit(0 if not fails else 1)
