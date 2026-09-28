#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 Room 迁移 DDL 与导出 schema 的一致性（离线，无需设备）。

用途
----
Room 在打开数据库时会对设备上实际的 schema 与 `AppDatabase` 的当前实体定义做
逐对象校验（表名 / 列名 / notNull / 默认值 / 索引名 / 索引唯一性…）。任何不一致都会
抛出 `IllegalStateException: Migration didn't properly handle ...`，也就是**升级即崩溃**。

本脚本用 Room 自己导出的 schema JSON（`core/database/schemas/**/N.json`，其中
`createSql` 即 Room 的期望终态）来**静态证明**某条迁移的 DDL 恰好把旧版本带到新版本：

    MIGRATION_A_B 的全部 execSQL 语句
        ==  B.json 的全部 createSql  -  A.json 的全部 createSql

即「迁移后 DB 恰好等于 B 版本的期望 schema」。因为两边都是 Room 自己的 DDL 文本，
逐字相等是充分的（Room 的校验本质就是文本比对）。

用法
----
    python tools/verify_migration_schema.py                  # 校验 44 -> 45（默认）
    python tools/verify_migration_schema.py --from 43 --to 44
    python tools/verify_migration_schema.py --list           # 列出所有可用 schema 版本

退出码：0 = 通过，1 = 失败（可用于 CI / 提交前自检）。

注意
----
* 只做**单一版本对**的校验。跨多条迁移的传递性需逐对调用。
* 迁移内若包含 `ALTER TABLE ... ADD COLUMN` 之类的**就地修改**，本脚本无法用
  「createSql 差集」直接表达（ADD COLUMN 的期望终态是 *重建后的整表* createSql）。
  此时会报告为「失配」并列出差异，需人工按 `addColumnIfMissing` 语义逐一核对。
  纯增量迁移（仅 CREATE TABLE/INDEX IF NOT EXISTS）可完整自动校验。
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_DIR = os.path.join(
    REPO, 'core', 'database', 'schemas', 'com.yunian.ai.database.AppDatabase'
)
APP_DATABASE = os.path.join(
    REPO, 'core', 'database', 'src', 'main', 'java', 'com', 'yunian', 'ai',
    'database', 'AppDatabase.kt',
)


def norm(sql: str) -> str:
    """归一化 SQL 空白（SQL 中字符串字面量之外空白无意义）。"""
    return re.sub(r'\s+', ' ', sql).strip()


def collect(schema_path: str) -> dict[str, str]:
    """收集某版本 schema 中所有对象的 createSql -> 对象标识。"""
    with io.open(schema_path, 'r', encoding='utf-8') as f:
        doc = json.load(f)
    db = doc.get('database', {})
    out: dict[str, str] = {}

    for entity in db.get('entities', []):
        table = entity.get('tableName', '?')

        def sub(sql: str, _t: str = table) -> str:
            # Room 导出的 createSql 用 ${TABLE_NAME} 作占位符
            return sql.replace('${TABLE_NAME}', _t)

        sql = entity.get('createSql')
        if sql:
            out[norm(sub(sql))] = 'table:' + table
        for idx in entity.get('indices') or []:
            isql = idx.get('createSql')
            if isql:
                out[norm(sub(isql))] = 'index:' + str(idx.get('name', '?'))

    for view in db.get('views') or []:
        vsql = view.get('createSql')
        if vsql:
            out[norm(vsql)] = 'view:' + str(view.get('viewName', '?'))

    return out


def extract_migration_block(app_db_src: str, old: int, new: int) -> str:
    """按花括号平衡扫描，取出 MIGRATION_<old>_<new> 的代码块。"""
    marker = r'val MIGRATION_%d_%d = object : Migration\(%d,\s*%d\)\s*\{' % (
        old, new, old, new,
    )
    m = re.search(marker, app_db_src)
    if not m:
        raise SystemExit(
            'ERROR: AppDatabase.kt 中找不到 MIGRATION_%d_%d 的定义' % (old, new)
        )
    depth, i, start = 1, m.end(), m.end()
    while i < len(app_db_src) and depth > 0:
        c = app_db_src[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        i += 1
    return app_db_src[start:i]


def extract_exec_sql(block: str) -> dict[str, str]:
    """提取 execSQL("...") 中的 DDL 字面量。"""
    # DDL 内部只使用反引号，不含裸双引号，故按 "..." 匹配即可
    raw = re.findall(r'execSQL\(\s*"((?:[^"\\]|\\.)*)"', block)
    out: dict[str, str] = {}
    for s in raw:
        unescaped = s.replace('\\"', '"').replace('\\\\', '\\')
        out[norm(unescaped)] = 'migration'
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--from', dest='old', type=int, default=44)
    ap.add_argument('--to', dest='new', type=int, default=45)
    ap.add_argument('--list', action='store_true', help='列出可用 schema 版本')
    args = ap.parse_args()

    if args.list:
        vers = sorted(
            int(f[:-5]) for f in os.listdir(SCHEMA_DIR) if f.endswith('.json')
        )
        print('可用 schema 版本：%s' % vers)
        return 0

    old_json = os.path.join(SCHEMA_DIR, '%d.json' % args.old)
    new_json = os.path.join(SCHEMA_DIR, '%d.json' % args.new)
    for p in (old_json, new_json):
        if not os.path.exists(p):
            raise SystemExit('ERROR: schema 文件不存在：%s' % p)

    s_old = collect(old_json)
    s_new = collect(new_json)
    only_new = {k: s_new[k] for k in s_new if k not in s_old}
    only_old = {k: s_old[k] for k in s_old if k not in s_new}

    with io.open(APP_DATABASE, 'r', encoding='utf-8') as f:
        src = f.read()
    block = extract_migration_block(src, args.old, args.new)
    mig = extract_exec_sql(block)

    print('%-28s %s' % ('v%d 对象数' % args.old, len(s_old)))
    print('%-28s %s' % ('v%d 对象数' % args.new, len(s_new)))
    print('%-28s %s' % ('delta（v%d 新增）' % args.new, len(only_new)))
    print('%-28s %s' % ('delta（v%d 被删）' % args.old, len(only_old)))
    print('%-28s %s' % ('MIGRATION execSQL 条数', len(mig)))
    print()

    missing = [k for k in only_new if k not in mig]
    extra = [k for k in mig if k not in only_new]

    print('=' * 74)
    ok = not missing and not extra and not only_old
    if ok:
        print('PASS  MIGRATION_%d_%d 的 DDL 与 v%d-v%d 差集完全一致（%d 个对象）'
              % (args.old, args.new, args.new, args.old, len(mig)))
        print('      => 运行时 Room schema 校验必过（无 IllegalStateException 风险）')
    else:
        if only_old:
            print('FAIL  v%d 有 %d 个对象在 v%d 中消失（迁移可能丢表/丢索引）：'
                  % (args.old, len(only_old), args.new))
            for k in only_old:
                print('   - [%s] %s' % (only_old[k], k[:150]))
        if missing:
            print('FAIL  迁移漏建 %d 个对象：' % len(missing))
            for k in missing:
                print('   - [%s] %s' % (only_new[k], k[:150]))
        if extra:
            print('FAIL  迁移多建 / DDL 文本失配 %d 条：' % len(extra))
            for k in extra:
                print('   - %s' % k[:180])
        print()
        print('提示：若迁移含 ADD COLUMN 等就地变更，请按 addColumnIfMissing 语义人工核对。')
    print('=' * 74)

    if ok:
        print()
        print('delta 明细（全部由迁移创建）：')
        for k, v in sorted(only_new.items(), key=lambda x: (x[1].split(':')[0], x[1])):
            print('  [OK] %-34s %s' % (v, k[:100]))

    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
