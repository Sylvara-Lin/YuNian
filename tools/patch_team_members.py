# -*- coding: utf-8 -*-
"""One-shot patch: replace the teamMembers list in TeamScreen.kt with the
6 core members from lianyu.chat, keeping the file's original \\uXXXX
uppercase-escape style for non-ASCII characters."""

import io
import sys

PATH = r"H:\susu\feature\profile\src\main\java\com\yunian\ai\feature\profile\TeamScreen.kt"

# (name, role, description, color, drawable resource name)
MEMBERS = [
    ("林梓涵", "创建者 & 全栈", "架构设计 · 全栈开发", "0xFF07C160", "team_linruoxo"),
    ("祈愿小苏", "安全加密", "JNI 安全 · 后端服务", "0xFF3498DB", "team_qiyuanxiaosu"),
    ("鸢祀", "多模态", "图像处理 · 语音识别", "0xFF9B59B6", "team_yuansi"),
    ("青思雨", "PC 端开发", "桌面客户端 · 跨平台", "0xFFCCA8E9", "team_qingsiyu"),
    ("Clove.", "软件基础开发", "底层开发", "0xFFE67E22", "team_clove"),
    ("寒拂晴ColdBreeze", "功能开发", "语音通话 · bug修复", "0xFFE85D75", "team_hanfuqing"),
]

# 1-indexed inclusive line range of the old `val teamMembers = listOf(...)` block.
START_LINE = 63
END_LINE = 113


def esc(text: str) -> str:
    """Escape every non-ASCII char as \\uXXXX (uppercase hex), like the original file."""
    return "".join(
        ch if ord(ch) < 128 else "\\u%04X" % ord(ch) for ch in text
    )


def build_block() -> list:
    lines = ["val teamMembers = listOf("]
    for index, (name, role, desc, color, avatar) in enumerate(MEMBERS):
        lines.append("    TeamMember(")
        lines.append('        name = "%s",' % esc(name))
        lines.append('        role = "%s",' % esc(role))
        lines.append('        description = "%s",' % esc(desc))
        lines.append("        color = Color(%s)," % color)
        lines.append("        avatarRes = R.drawable.%s" % avatar)
        lines.append("    )" + ("," if index < len(MEMBERS) - 1 else ""))
    lines.append(")")
    return lines


def main() -> int:
    with io.open(PATH, "r", encoding="utf-8", newline="") as handle:
        source = handle.readlines()
    first = source[START_LINE - 1].strip()
    last = source[END_LINE - 1].strip()
    assert first == "val teamMembers = listOf(", "unexpected start line: %r" % first
    assert last == ")", "unexpected end line: %r" % last

    block = [line + "\n" for line in build_block()]
    patched = source[: START_LINE - 1] + block + source[END_LINE:]
    with io.open(PATH, "w", encoding="utf-8", newline="") as handle:
        handle.writelines(patched)
    print("patched %d lines -> %d lines" % (END_LINE - START_LINE + 1, len(block)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
