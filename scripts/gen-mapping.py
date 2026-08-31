# java-only: C↔Java 映射表生成器 —— 扫描文件头 ref:/diff:/java-only 与代码内 "// C文件: C函数" 注释，
#   产出 docs/mapping.md。表是生成物，不要手改；口径变更改本脚本。
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "luajvm-core", "src", "main", "java", "org", "luajvm")
OUT = os.path.join(ROOT, "docs", "mapping.md")

MAP_RE = re.compile(
    r"^\s*//\s*([a-z][a-z0-9_]*\.[ch])\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:[-—–]\s*(.*\S))?\s*$")
REF_RE = re.compile(r"^//\s*ref:\s*(.+?)\s*$")
DIFF_RE = re.compile(r"^//\s*diff:\s*(.+?)\s*$")
JONLY_RE = re.compile(r"^//\s*java-only:\s*(.*\S)?\s*$")
CTRL = {"if", "for", "while", "switch", "catch", "return", "new", "do", "else",
        "try", "synchronized", "throw", "assert", "case", "default", "break", "continue"}


def next_code_name(lines, i):
    """取映射注释之后第一条代码行，猜测其归属的 Java 方法/字段/类型名。"""
    for j in range(i + 1, min(i + 14, len(lines))):
        s = lines[j].strip()
        if not s or s.startswith(("//", "/*", "*", "@")):
            continue
        m = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(", s)
        if m and m.group(1) not in CTRL:
            return m.group(1)
        m = re.search(r"\b(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)", s)
        if m:
            return m.group(1)
        m = re.match(r"[A-Za-z_<>,\.\[\]\s]*?([A-Za-z_][A-Za-z0-9_]*)\s*(=[^=]|;)", s)
        if m:
            return m.group(1)
        return None
    return None


def scan():
    """返回 (file_level, func_level, javaonly)。"""
    file_level = []   # (java_pkg_class, [c files], diff_summary)
    func_level = OrderedDict()  # cfile -> OrderedDict((cfunc, javamethod) -> javafile)
    javaonly = []     # (java_pkg_class, desc)
    for dp, dns, fns in os.walk(SRC):
        # os.walk 按文件系统顺序给目录，排序后遍历才能在不同机器上产出同一份表
        dns.sort()
        for fn in sorted(fns):
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dp, fn)
            rel = os.path.relpath(path, SRC).replace("\\", "/")[:-5]
            pkgcls = rel.replace("/", ".")
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read()
            lines = text.splitlines()
            head = lines[:15]
            refs, diffs = [], []
            jonly = None
            for h in head:
                m = REF_RE.match(h)
                if m:
                    refs += re.findall(r"[a-z][a-z0-9_]*\.[ch]", m.group(1))
                m = DIFF_RE.match(h)
                if m:
                    diffs.append(m.group(1))
                m = JONLY_RE.match(h)
                if m:
                    jonly = m.group(1) or ""
            if jonly is not None:
                jonly = re.sub(r"[，、—-]\s*$", "", jonly)
                javaonly.append((pkgcls, jonly))
            elif refs:
                file_level.append((pkgcls, refs, "；".join(diffs).replace("|", "\\|")))
            for i, ln in enumerate(lines):
                m = MAP_RE.match(ln)
                if not m:
                    continue
                cfile, cfunc = m.group(1), m.group(2)
                jname = next_code_name(lines, i)
                func_level.setdefault(cfile, OrderedDict())\
                          .setdefault((cfunc, jname), rel.split("/")[-1])
    return file_level, func_level, javaonly


def main():
    file_level, func_level, javaonly = scan()
    out = []
    w = out.append
    w("# C ↔ Java 映射表\n")
    w("> 本表由 `scripts/gen-mapping.py` 从代码注释自动生成，**不要手改**；口径变更改脚本后重新生成。")
    w("> 口径：文件级来自文件头 `// ref:` / `// diff:`；函数级来自代码内 `// <C文件>: <C函数>` 注释；")
    w("> Java 方法名取映射注释后的第一条代码行，个别为字段/类型名。")
    w("> 热路径写法不对齐 C 的类（HOT-EXEMPT）语义仍与本表对应，见 docs/performance.md。\n")

    w("## 一、文件级映射（翻译自 C 的类）\n")
    w("| C 源文件 | Java 类 | 类级差异（文件头 diff） |")
    w("|---|---|---|")
    rows = []
    for pkgcls, refs, diff in file_level:
        for cf in refs:
            rows.append((cf, pkgcls, diff))
    for cf, pkgcls, diff in sorted(rows):
        w(f"| {cf} | `{pkgcls}` | {diff or '—'} |")
    w("")

    w("## 二、函数级映射（按 C 源文件分组）\n")
    for cfile in sorted(func_level):
        w(f"### {cfile}\n")
        w("| C 函数 | Java 位置 |")
        w("|---|---|")
        for (cfunc, jname), jfile in sorted(func_level[cfile].items(),
                                            key=lambda kv: (kv[0][0], kv[0][1] or "")):
            loc = f"`{jfile}#{jname}`" if jname else f"`{jfile}`"
            w(f"| `{cfunc}` | {loc} |")
        w("")

    w("## 三、Java 独有层（无 C 对应）\n")
    w("`luajvm-core` 中带 `// java-only:` 文件头的类（本脚本只扫 core 源码树）：\n")
    w("| Java 类 | 说明 |")
    w("|---|---|")
    for pkgcls, desc in sorted(javaonly):
        w(f"| `{pkgcls}` | {desc or '—'} |")
    w("")
    w("- `luajvm-android` 模块整体为平台绑定层（AGENTS.md 规定无文件头要求），Lua 可见面契约见 AGENTS.md「改名硬约束」。")
    w("- 门禁任务与判据见 docs/GATES.md。\n")
    w("## 四、再生成\n")
    w("```bash")
    w("python scripts/gen-mapping.py   # 重新扫描并覆盖 docs/mapping.md")
    w("```\n")

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    n_func = sum(len(v) for v in func_level.values())
    print(f"files: file_level={len(file_level)} func_level={n_func} javaonly={len(javaonly)} -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
