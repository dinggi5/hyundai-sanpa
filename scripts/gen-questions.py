#!/usr/bin/env python3
"""질문_초벌.md → app/.../QuestionBank.kt. 질문을 고치면 이것만 다시 돌린다(그 뒤 APK 다시).

읽는 꼴:
  ## N. 범주이름
  ### 주제id · 주제이름 · 쉬운 질문|적당한 질문|어려운 질문 [★]
  1. 질문
  ... (넷)
주제마다 질문이 꼭 넷이어야 하고, 범주는 여섯이어야 한다. 어긋나면 멈춘다.
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "질문_초벌.md"
OUT = ROOT / "app/src/main/java/studio/scopic/sanpa/QuestionBank.kt"
LEVELS = {"쉬운 질문": "EASY", "적당한 질문": "MID", "어려운 질문": "HARD"}

def kt(s): return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'

cats, topics, cur = [], [], None
for n, line in enumerate(SRC.read_text(encoding="utf-8").splitlines(), 1):
    line = line.rstrip()
    if m := re.match(r"^## \d+\.\s*(.+)$", line):
        cats.append(m.group(1).strip()); cur = None
    elif m := re.match(r"^### ([a-z0-9-]+) · (.+?) · (쉬운 질문|적당한 질문|어려운 질문)(\s*★)?$", line):
        if not cats: sys.exit(f"{n}행: 범주 전에 주제가 있다")
        cur = dict(id=m.group(1), name=m.group(2).strip(), level=LEVELS[m.group(3)], star=bool(m.group(4)), cat=len(cats) - 1, qs=[])
        topics.append(cur)
    elif line.startswith("### "):
        sys.exit(f"{n}행: 주제 제목 꼴이 아니다 — `### id · 이름 · 쉬운 질문|적당한 질문|어려운 질문 [★]`: {line}")
    elif cur is not None and (m := re.match(r"^\d+\.\s+(.+)$", line)):
        cur["qs"].append(m.group(1).strip())
    elif line.startswith("---") or line.startswith("| "):
        cur = None

if len(cats) != 6: sys.exit(f"범주가 {len(cats)}개다(여섯이어야 한다): {cats}")
ids = [t["id"] for t in topics]
if len(set(ids)) != len(ids): sys.exit("주제 id 가 겹친다")
for t in topics:
    if len(t["qs"]) != 4: sys.exit(f"{t['id']}: 질문이 {len(t['qs'])}개다(넷이어야 한다)")

out = ["package studio.scopic.sanpa", "",
       "// 🔴 만든 파일 — 손으로 고치지 말 것. 질문_초벌.md 를 고치고 `python3 scripts/gen-questions.py` 를 돌린다.", "",
       "object QuestionBank {",
       "    val categories = listOf(" + ", ".join(kt(c) for c in cats) + ")", "",
       "    val topics = listOf("]
for t in topics:
    out.append(f"        Topic({kt(t['id'])}, {kt(t['name'])}, {t['cat']}, Level.{t['level']}, {str(t['star']).lower()}, listOf(")
    for q in t["qs"]: out.append(f"            {kt(q)},")
    out.append("        )),")
out += ["    )", "}", ""]
OUT.write_text("\n".join(out), encoding="utf-8")
print(f"주제 {len(topics)} · 범주 {len(cats)} → {OUT.relative_to(ROOT)}")
