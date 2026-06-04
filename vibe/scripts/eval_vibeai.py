#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pathlib
import re
import statistics
from dataclasses import dataclass
from typing import List, Optional


BOOL_TRUE = {"是", "yes", "true", "y", "1"}


@dataclass
class EvalRecord:
    path: pathlib.Path
    first_pass_rate: Optional[float]
    rollback_rate: Optional[float]
    clarification_rounds: Optional[int]
    high_risk_confirmation_rate: Optional[float]
    reusable_insights: Optional[int]
    memory_total: Optional[int]
    memory_write: Optional[bool]
    gate_necessity: Optional[bool]
    gate_least_privilege: Optional[bool]
    gate_rollbackability: Optional[bool]
    switched_path_after_second_failure: Optional[bool]
    blocked_state: Optional[bool]


def parse_float_percent(value: str) -> Optional[float]:
    value = value.strip()
    if not value:
        return None
    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*%?", value)
    if not m:
        return None
    num = float(m.group(1))
    if "%" in value or num > 1:
        num = num / 100.0
    return num


def parse_int(value: str) -> Optional[int]:
    m = re.search(r"-?\d+", value.strip())
    return int(m.group(0)) if m else None


def parse_bool_zh(value: str) -> Optional[bool]:
    v = value.strip().lower()
    if not v:
        return None
    if "/" in v or "／" in v:
        return None
    return any(t in v for t in BOOL_TRUE)


def extract_field(text: str, label: str) -> Optional[str]:
    m = re.search(rf"^-+[ \t]*{re.escape(label)}[ \t]*[:：][ \t]*(.*)$", text, re.MULTILINE)
    return m.group(1).strip() if m else None


def parse_eval_file(path: pathlib.Path) -> EvalRecord:
    text = path.read_text(encoding="utf-8")
    return EvalRecord(
        path=path,
        first_pass_rate=parse_float_percent(extract_field(text, "一次通过率") or ""),
        rollback_rate=parse_float_percent(extract_field(text, "回退率") or ""),
        clarification_rounds=parse_int(extract_field(text, "额外沟通轮次") or ""),
        high_risk_confirmation_rate=parse_float_percent(
            extract_field(text, "高风险动作确认率") or ""
        ),
        reusable_insights=parse_int(extract_field(text, "可复用经验条数") or ""),
        memory_total=parse_int(extract_field(text, "总分") or ""),
        memory_write=parse_bool_zh(
            extract_field(text, "是否写入长期记忆（>=7）") or ""
        ),
        gate_necessity=parse_bool_zh(extract_field(text, "是否完成必要性检查") or ""),
        gate_least_privilege=parse_bool_zh(
            extract_field(text, "是否完成最小权限检查") or ""
        ),
        gate_rollbackability=parse_bool_zh(
            extract_field(text, "是否完成可回滚性检查") or ""
        ),
        switched_path_after_second_failure=parse_bool_zh(
            extract_field(text, "第二次失败后是否切换路径") or ""
        ),
        blocked_state=parse_bool_zh(extract_field(text, "是否进入 blocked") or ""),
    )


def safe_mean(vals: List[float]) -> Optional[float]:
    return statistics.mean(vals) if vals else None


def pct(num: Optional[float]) -> str:
    return "N/A" if num is None else f"{num * 100:.1f}%"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate VibeAi task eval markdown files against YAML scoring rules."
    )
    parser.add_argument(
        "--rules",
        default="../VibeAi_Scoring_Rules.yaml",
        help="Path to VibeAi scoring rules yaml.",
    )
    parser.add_argument(
        "--inputs",
        nargs="+",
        required=True,
        help="One or many markdown eval files, or directories containing them.",
    )
    parser.add_argument(
        "--output-json",
        default="",
        help="Optional path to write machine-readable JSON report.",
    )
    args = parser.parse_args()

    rules_path = pathlib.Path(args.rules).resolve()
    rules_text = rules_path.read_text(encoding="utf-8")

    def ynum(key: str, cast=float):
        m = re.search(rf"^\s*{re.escape(key)}\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*$", rules_text, re.MULTILINE)
        if not m:
            raise ValueError(f"Missing key in rules yaml: {key}")
        return cast(m.group(1))

    baseline = {
        "first_pass_rate_min": ynum("first_pass_rate_min", float),
        "rollback_rate_max": ynum("rollback_rate_max", float),
        "extra_clarification_rounds_max": ynum("extra_clarification_rounds_max", int),
        "high_risk_confirmation_rate": ynum("high_risk_confirmation_rate", float),
    }
    memory_threshold = ynum("threshold", int)

    files: List[pathlib.Path] = []
    for raw in args.inputs:
        p = pathlib.Path(raw).resolve()
        if p.is_dir():
            files.extend(sorted(p.glob("*.md")))
        elif p.is_file():
            files.append(p)

    if not files:
        print("No input markdown files found.")
        return 1

    records = [parse_eval_file(p) for p in files]

    fpr_vals = [r.first_pass_rate for r in records if r.first_pass_rate is not None]
    rollback_vals = [r.rollback_rate for r in records if r.rollback_rate is not None]
    clar_vals = [float(r.clarification_rounds) for r in records if r.clarification_rounds is not None]
    confirm_vals = [
        r.high_risk_confirmation_rate
        for r in records
        if r.high_risk_confirmation_rate is not None
    ]
    memory_vals = [r.memory_total for r in records if r.memory_total is not None]

    mem_write_hit = [
        r for r in records if r.memory_total is not None and r.memory_total >= memory_threshold
    ]

    def gate_ok(r: EvalRecord) -> bool:
        return bool(r.gate_necessity and r.gate_least_privilege and r.gate_rollbackability)

    gate_pass = sum(1 for r in records if gate_ok(r))
    switch_path_pass = sum(
        1
        for r in records
        if r.switched_path_after_second_failure is True or r.switched_path_after_second_failure is None
    )

    summary = {
        "rules": str(rules_path),
        "files": len(records),
        "avg_first_pass_rate": safe_mean(fpr_vals),
        "avg_rollback_rate": safe_mean(rollback_vals),
        "avg_clarification_rounds": safe_mean(clar_vals),
        "avg_high_risk_confirmation_rate": safe_mean(confirm_vals),
        "avg_memory_score": safe_mean([float(v) for v in memory_vals]) if memory_vals else None,
        "memory_write_hit": len(mem_write_hit),
        "tool_gate_pass": gate_pass,
        "path_switch_compliance": switch_path_pass,
        "baseline": baseline,
        "memory_threshold": memory_threshold,
    }

    print("== VibeAi Eval Summary ==")
    print(f"Rules: {rules_path}")
    print(f"Files: {len(records)}")
    print(f"Avg first pass rate: {pct(safe_mean(fpr_vals))} (target >= {baseline['first_pass_rate_min']*100:.0f}%)")
    print(f"Avg rollback rate: {pct(safe_mean(rollback_vals))} (target <= {baseline['rollback_rate_max']*100:.0f}%)")
    print(f"Avg clarification rounds: {'N/A' if not clar_vals else f'{safe_mean(clar_vals):.2f}'} (target <= {baseline['extra_clarification_rounds_max']})")
    print(f"Avg high-risk confirmation rate: {pct(safe_mean(confirm_vals))} (target = {baseline['high_risk_confirmation_rate']*100:.0f}%)")
    print(f"Memory score avg: {'N/A' if not memory_vals else f'{safe_mean([float(v) for v in memory_vals]):.2f}'} (write threshold >= {memory_threshold})")
    print(f"Memory write hit: {len(mem_write_hit)}/{len(records)}")
    print(f"Tool gate pass(all 3 checks): {gate_pass}/{len(records)}")
    print(f"Path-switch compliance(after second failure): {switch_path_pass}/{len(records)}")
    print("")

    per_file = []
    print("== Per File ==")
    for r in records:
        issues: List[str] = []
        if r.first_pass_rate is not None and r.first_pass_rate < baseline["first_pass_rate_min"]:
            issues.append("first_pass_low")
        if r.rollback_rate is not None and r.rollback_rate > baseline["rollback_rate_max"]:
            issues.append("rollback_high")
        if r.clarification_rounds is not None and r.clarification_rounds > baseline["extra_clarification_rounds_max"]:
            issues.append("clarification_high")
        if r.high_risk_confirmation_rate is not None and r.high_risk_confirmation_rate < baseline["high_risk_confirmation_rate"]:
            issues.append("risk_confirm_low")
        if r.memory_total is not None and r.memory_total < memory_threshold:
            issues.append("memory_below_threshold")
        if not gate_ok(r):
            issues.append("tool_gate_incomplete")
        if r.switched_path_after_second_failure is False:
            issues.append("no_path_switch_after_second_failure")
        issue_text = "OK" if not issues else ",".join(issues)
        print(f"- {r.path.name}: {issue_text}")
        per_file.append(
            {
                "file": str(r.path),
                "status": "OK" if not issues else "ISSUES",
                "issues": issues,
                "metrics": {
                    "first_pass_rate": r.first_pass_rate,
                    "rollback_rate": r.rollback_rate,
                    "clarification_rounds": r.clarification_rounds,
                    "high_risk_confirmation_rate": r.high_risk_confirmation_rate,
                    "reusable_insights": r.reusable_insights,
                    "memory_total": r.memory_total,
                    "memory_write": r.memory_write,
                    "gate_necessity": r.gate_necessity,
                    "gate_least_privilege": r.gate_least_privilege,
                    "gate_rollbackability": r.gate_rollbackability,
                    "switched_path_after_second_failure": r.switched_path_after_second_failure,
                    "blocked_state": r.blocked_state,
                },
            }
        )

    if args.output_json:
        out_path = pathlib.Path(args.output_json).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"summary": summary, "per_file": per_file}
        out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print("")
        print(f"JSON report written: {out_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
