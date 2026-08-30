"""Probes the cadence model for the signatures that separate code typing from prose typing.

Checks three claims:
  1. Pause structure -- real code typing is heavy-tailed (multi-second thinking
     pauses between constructs). Does the model produce any?
  2. Identifier speed -- LanguageModel classifies by English word difficulty.
     What does that do to real code identifiers?
  3. Token coverage -- how much of a code corpus does the English COMMON_WORDS
     list actually recognise?
"""
import random, statistics

import cadence_sim as S

CODE = S.CODE


def pause_structure(wpm, trials=20):
    delays = []
    for _ in range(trials):
        p, _m = S.human_plan(CODE, wpm)
        p3 = S.calibrate(S.auto_indent(S.syntax_decel(p), True), wpm)
        delays += [d for d, c, bs in p3]
    delays.sort()

    def pct(q):
        return delays[int(len(delays) * q)]

    print(f"  inter-key delay distribution @ {wpm} WPM (n={len(delays)})")
    print(f"    p50={pct(.50):5d}ms  p90={pct(.90):5d}ms  p99={pct(.99):5d}ms  max={delays[-1]:5d}ms")
    for thresh in (1000, 2000, 3000):
        n = sum(1 for d in delays if d >= thresh)
        print(f"    pauses >= {thresh/1000:.0f}s: {n:4d}  ({n/len(delays):.2%})")
    print()


def identifier_speed():
    idents = ["maxSubArray", "nums", "best", "cur", "Math", "max", "length",
              "i", "int", "for", "return", "public", "result", "leftPointer",
              "dp", "visited", "adjacencyList", "helper", "temp", "ans"]
    print("  LanguageModel.getWordDifficulty on real code identifiers:")
    buckets = {"COMMON": [], "NORMAL": [], "COMPLEX": []}
    for w in idents:
        buckets[S.difficulty(w)].append(w)
    for k in ("COMMON", "NORMAL", "COMPLEX"):
        effect = {"COMMON": "0.6x time, 0.5x errors",
                  "NORMAL": "no adjustment",
                  "COMPLEX": "1.3x time, 1.5x errors"}[k]
        print(f"    {k:8s} ({effect:24s}): {', '.join(buckets[k]) or '-'}")
    print()


def token_coverage():
    import re
    toks = [t for t in re.findall(r"[A-Za-z_][A-Za-z_0-9]*", CODE)]
    common = [t for t in toks if S.difficulty(t) == "COMMON"]
    complexs = [t for t in toks if S.difficulty(t) == "COMPLEX"]
    print(f"  Java sample: {len(toks)} identifier tokens")
    print(f"    classified COMMON  (speed boost): {len(common):3d}  ({len(common)/len(toks):.1%})")
    print(f"    classified COMPLEX (speed penalty + more errors): {len(complexs):3d}  ({len(complexs)/len(toks):.1%})")
    print(f"    -> code gets the penalty path; prose gets the boost path.")
    print()


if __name__ == "__main__":
    random.seed(31)
    print("=== 1. PAUSE STRUCTURE ===")
    pause_structure(60)
    pause_structure(150)
    print("=== 2. IDENTIFIER SPEED MODEL ===")
    identifier_speed()
    print("=== 3. LANGUAGE MODEL COVERAGE ON CODE ===")
    token_coverage()
