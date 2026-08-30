"""Inter-key delay distribution of the cadence pipeline.

Reports where delays actually land, and in particular how much of the
distribution piles up on applyWpmCalibration's hard 30 ms floor.
"""
import random, statistics
from cadence_sim import human_plan, syntax_decel, auto_indent, calibrate, PROSE, CODE

random.seed(11)


def analyse(text, wpm, code_mode, label):
    floors = alnum = zero = total = 0
    alnum_d, sym_d = [], []
    for _ in range(30):
        p, m = human_plan(text, wpm)
        p3 = calibrate(auto_indent(syntax_decel(p), code_mode), wpm)
        for d, c, bs in p3:
            total += 1
            if d == 0:
                zero += 1
            if bs or c is None:
                continue
            if c.isalnum():
                alnum += 1
                alnum_d.append(d)
                if d == 30:
                    floors += 1
            elif not c.isspace():
                sym_d.append(d)

    print(f"{label} @ {wpm} WPM")
    print(f"  alnum delays  median={statistics.median(alnum_d):6.1f}ms  "
          f"mean={statistics.mean(alnum_d):6.1f}  "
          f"pinned at 30ms floor: {floors / alnum:5.1%}")
    if sym_d:
        print(f"  symbol delays median={statistics.median(sym_d):6.1f}ms  "
              f"mean={statistics.mean(sym_d):6.1f}")
    print(f"  zero-delay strokes (sent back-to-back at HID gap only): {zero / total:5.1%}")
    print()


if __name__ == "__main__":
    analyse(PROSE, 60, False, "PROSE")
    analyse(PROSE, 150, False, "PROSE")
    analyse(CODE, 60, True, "JAVA")
    analyse(CODE, 150, True, "JAVA")
