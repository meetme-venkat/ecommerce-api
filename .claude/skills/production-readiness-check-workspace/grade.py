"""Grade production-readiness reports against eval assertions.

Each assertion is matched by a predicate over the report text. Predicates are
deliberately conservative: they look for the substantive claim, not exact wording,
so a differently-phrased-but-correct report still passes.

Usage: python grade.py <iteration-dir>
"""
import json
import re
import sys
from pathlib import Path


def sections(text):
    """Split report into {severity_heading: body} so we can tell where a finding landed."""
    out, cur, buf = {}, "_preamble", []
    for line in text.splitlines():
        m = re.match(r"^##\s+(.*)", line)
        if m:
            out[cur] = "\n".join(buf)
            cur, buf = m.group(1).strip().lower(), []
        else:
            buf.append(line)
    out[cur] = "\n".join(buf)
    return out


SEV_WORDS = ("critical", "warning", "info", "high", "medium", "low", "blocker", "severe")
CLEAN_WORDS = ("pass", "good", "clean", "strength", "correct", "fine")


def flagged_sections(sec):
    """Body text of the severity buckets only (not the clean/passed section).

    Accepts synonyms: a report calling them 'Blockers / High / Medium' is grouped by
    severity just as much as one saying 'Critical / Warning / Info'. Grading the
    vocabulary instead of the substance would manufacture a fake win for the skill,
    whose template happens to use the exact words this script was first written around.
    """
    return "\n".join(
        v for k, v in sec.items()
        if any(w in k for w in SEV_WORDS) and not any(c in k for c in CLEAN_WORDS)
    )


def critical_body(sec):
    return "\n".join(v for k, v in sec.items()
                     if any(w in k for w in ("critical", "blocker", "severe")))


def passed_body(sec):
    return "\n".join(v for k, v in sec.items() if any(c in k for c in CLEAN_WORDS))


def has(text, *pats):
    return any(re.search(p, text, re.I | re.S) for p in pats)


# --- predicates -------------------------------------------------------------

def p_verdict_not_ready(t, sec):
    head = t[:600]
    return bool(re.search(r"not\s*ready|no[\s\-]*go|do\s*not\s*ship|don'?t\s*ship", head, re.I))


def p_blank_password(t, sec):
    return has(flagged_sections(sec), r"password").__and__(
        has(t, r"blank|empty|no password|password\s*=\s*$|password=\s*\n", r"datasource\.password")
    )


def p_oversell_race(t, sec):
    return has(t, r"oversell|over-sell|race", ) and has(t, r"stock")


def p_file_line(t, sec):
    body = flagged_sections(sec)
    bullets = [b for b in re.findall(r"^\s*[-*]\s+\*\*\[", body, re.M)]
    cites = re.findall(r"\.(java|properties|sql|xml):\d+", body)
    return len(cites) >= max(3, len(bullets) // 2)


def p_grouped(t, sec):
    keys = " ".join(sec.keys())
    return sum(w in keys for w in SEV_WORDS) >= 2


def p_no_ddl_false_positive(t, sec):
    """ddl-auto=validate must NOT appear as a flagged finding."""
    flagged = flagged_sections(sec)
    for line in flagged.splitlines():
        if re.search(r"ddl-auto", line, re.I) and re.search(r"validate", line, re.I):
            # mentioning it as the *fix* is fine
            if not re.search(r"fix:|set .*to .*validate|should be", line, re.I):
                return False
    return True


def p_credits_passed(t, sec):
    return len(passed_body(sec).strip()) > 40


def p_auth_critical(t, sec):
    return has(critical_body(sec) or t[:2000], r"authenticat|authoriz|security layer|spring-boot-starter-security")


def p_idor(t, sec):
    return has(t, r"idor", r"client[- ]supplied", r"customerId.*(body|trusted|client)", r"trusts?\s+the\s+client")


def p_oneToOne_eager(t, sec):
    return has(t, r"@OneToOne") and has(t, r"eager")


def p_pagination(t, sec):
    return has(t, r"pageable|pagination|unbounded (list|collection)|no paging")


def p_structured_severity(t, sec):
    """Recognizes the intent and produces a graded report rather than freeform prose."""
    keys = " ".join(sec.keys())
    sev_headings = sum(w in keys for w in ("critical", "warning", "info", "high", "medium", "low"))
    return sev_headings >= 2 or has(t, r"severity summary")


def p_fix_order(t, sec):
    """Criticals presented before lesser findings, or an explicit 'fix first' ordering."""
    idx = {}
    for i, line in enumerate(t.splitlines()):
        for w in ("critical", "blocker"):
            if re.match(rf"^#+.*{w}", line, re.I) and "crit" not in idx:
                idx["crit"] = i
        for w in ("warning", "high", "medium", "info"):
            if re.match(rf"^#+.*{w}", line, re.I) and "lesser" not in idx:
                idx["lesser"] = i
    ordered = "crit" in idx and "lesser" in idx and idx["crit"] < idx["lesser"]
    return ordered or has(t, r"fix (these )?first|start with the critical|minimum path|priority order|what to fix first")


def p_all_areas(t, sec):
    areas = {
        "config": r"config|application\.properties|password|ddl-auto",
        "transactions": r"@Transactional|transaction",
        "web": r"\bDTO\b|@Valid|validation|controller",
        "concurrency": r"concurren|race|oversell|lock|@Version",
        "n+1": r"n\+1|join fetch|EntityGraph|lazy",
    }
    return all(has(t, p) for p in areas.values())


def p_verdict_top(t, sec):
    """Verdict must be near the top - within the first few lines, not buried."""
    head = "\n".join(t.splitlines()[:8])
    return bool(re.search(r"verdict|not\s*ready|no[\s\-]*go|ready to ship|go/no-go", head, re.I))


def p_n1_flagged(t, sec):
    return has(flagged_sections(sec) or t, r"n\+1", r"join fetch", r"EntityGraph", r"per (order|row|product|review)")


# Assertions that cannot be judged from report.md alone (e.g. conversational
# behaviour, or side effects verified out-of-band). Excluded from the denominator
# rather than scored as failures - a grader that punishes the ungradeable is worse
# than one that admits the gap.
MANUAL = {
    "Ends by offering to fix (in severity order) and does NOT edit any repository files yet — waits for the user to choose":
        "offer-to-fix lives in the chat reply, not report.md; no-source-edits verified via git status (clean)",
}


PREDS = {
    "Produces a structured severity-graded readiness report (recognizes the intent despite no explicit 'production readiness' phrase)": p_structured_severity,
    "Lays out what to fix first, ordered by severity (criticals before warnings)": p_fix_order,
    "Report addresses all five areas: config, transactions, web layer (dto/validation/error-handling), and concurrency": p_all_areas,
    "A go/no-go verdict appears at the top of the report": p_verdict_top,
    "Flags at least one N+1 query risk as a warning": p_n1_flagged,
    "Gives a clear NOT READY / no-go verdict prominently (not a hedge)": p_verdict_not_ready,
    "Identifies the blank Postgres datasource password as a critical config finding": p_blank_password,
    "Identifies the unguarded stock read-check-write oversell race in OrderService.create as a critical concurrency finding": p_oversell_race,
    "Every finding cites a concrete file:line location": p_file_line,
    "Findings are grouped by severity (critical / warning / info)": p_grouped,
    "Does NOT wrongly flag ddl-auto=validate as a production risk (validate is correct for prod)": p_no_ddl_false_positive,
    "Credits at least one genuinely clean area as passed (DTO boundary, @Transactional coverage, or input validation)": p_credits_passed,
    "Identifies the complete absence of authentication/authorization on all endpoints as a critical finding": p_auth_critical,
    "Flags that customerId is supplied by the client in the request body (IDOR / trusting client identity)": p_idor,
    "Flags Order.payment @OneToOne defaulting to EAGER (no explicit fetch attribute) as a query/perf finding": p_oneToOne_eager,
    "Flags at least one unbounded list endpoint lacking pagination": p_pagination,
}


def grade(report_path, assertions):
    text = Path(report_path).read_text(encoding="utf-8", errors="replace")
    sec = sections(text)
    exps = []
    for a in assertions:
        txt = a["text"]
        # Prefix match so punctuation drift (em-dash vs hyphen) doesn't miss the entry.
        manual = next((v for k, v in MANUAL.items() if txt.startswith(k[:60])), None)
        if manual:
            exps.append({"text": txt, "passed": None, "evidence": f"MANUAL: {manual}"})
            continue
        fn = PREDS.get(txt)
        if fn is None:
            # Loud, not silent - an ungraded assertion is a bug in this script.
            exps.append({"text": txt, "passed": None,
                         "evidence": "NO PREDICATE DEFINED - excluded from score (fix grade.py)"})
            continue
        try:
            passed = bool(fn(text, sec))
            ev = "matched in report" if passed else "not found in report"
        except Exception as e:
            passed, ev = False, f"predicate error: {e}"
        exps.append({"text": txt, "passed": passed, "evidence": ev})

    scored = [e for e in exps if e["passed"] is not None]
    ok = sum(1 for e in scored if e["passed"])
    return {
        "expectations": exps,
        "summary": {
            "passed": ok,
            "failed": len(scored) - ok,
            "total": len(scored),
            "excluded": len(exps) - len(scored),
            "pass_rate": round(ok / len(scored), 4) if scored else 0.0,
        },
    }


def main(root):
    root = Path(root)
    for ev_dir in sorted(root.glob("eval-*")):
        meta = json.loads((ev_dir / "eval_metadata.json").read_text(encoding="utf-8"))
        for cfg in ("with_skill", "without_skill"):
            rp = ev_dir / cfg / "run-1" / "outputs" / "report.md"
            if not rp.exists():
                print(f"MISSING {rp}")
                continue
            g = grade(rp, meta["assertions"])
            out = ev_dir / cfg / "run-1" / "grading.json"
            out.write_text(json.dumps(g, indent=2), encoding="utf-8")
            s = g["summary"]
            print(f"{ev_dir.name:8} {cfg:14} {s['passed']}/{s['total']}  ({s['pass_rate']:.0%})")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
