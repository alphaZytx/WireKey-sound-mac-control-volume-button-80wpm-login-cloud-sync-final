"""Where does the time in a typing plan actually go?

Ranks the tunable constants by how many milliseconds each contributes, so
recalibration effort can be spent where it moves the number.

Markov-stage components are instrumented directly (exact). Post-processing
stages are measured as the delta each one adds to the plan's total (also
exact, since they run in sequence).
"""
import random, statistics
from collections import defaultdict

import cadence_sim as S


def instrumented_markov(text, wpm):
    """Re-runs the Markov sim, attributing each millisecond to a component."""
    m = S.Markov(text, max(20, min(150, wpm)))
    b = defaultdict(float)

    # Wrap the two time sources so every addition lands in a bucket.
    orig_kt = m.kt

    def kt(c):
        t = orig_kt(c)
        # Split the space/uppercase surcharge back out of the total.
        if c == ' ':
            b['space pause (TIME_SPACE_PAUSE_MEAN)'] += S.TIME_SPACE_PAUSE_MEAN
            b['base keystroke'] += t - S.TIME_SPACE_PAUSE_MEAN
        elif c.isupper():
            b['uppercase (TIME_UPPERCASE_PENALTY)'] += S.TIME_UPPERCASE_PENALTY
            b['base keystroke'] += t - S.TIME_UPPERCASE_PENALTY
        else:
            b['base keystroke'] += t
        return t

    m.kt = kt

    before = 0.0
    steps = 0
    cap = len(text) * 10
    while True:
        prev_time = m.time
        prev_hist = len(m.hist)
        r = m.step()
        if r is None:
            break
        steps += 1
        delta = m.time - prev_time
        if len(m.hist) > prev_hist:
            act = m.hist[-1][1]
            if act == "BACKSPACE":
                b['error correction (reaction + backspace + cognitive delay)'] += delta
            # TYPED/SWAP time was already bucketed inside kt(), except the
            # cognitive/no-key bypass path which never calls kt().
            elif act == "TYPED" and delta > 0:
                accounted = sum(b.values())
                if accounted < m.time - 1e-9:
                    b['base keystroke'] += (m.time - accounted)
        if steps > cap:
            break

    plan = []
    last = 0.0
    for ts, act, ch in m.hist:
        d = max(0, round((ts - last) * 1000))
        last = ts
        if act == "BACKSPACE":
            plan.append([d, None, True])
        elif act == "SWAP":
            plan.append([d, ch[0], False])
            plan.append([0, ch[1], False])
        else:
            plan.append([d, ch[0] if ch else None, False])

    return plan, {k: v * 1000.0 for k, v in b.items()}


def budget(text, wpm, code_mode, trials=20):
    agg = defaultdict(float)
    totals = []
    for _ in range(trials):
        plan, b = instrumented_markov(text, wpm)
        for k, v in b.items():
            agg[k] += v

        t0 = sum(d for d, c, bs in plan)
        p1 = S.syntax_decel(plan)
        t1 = sum(d for d, c, bs in p1)
        p2 = S.auto_indent(p1, code_mode)
        t2 = sum(d for d, c, bs in p2)
        p3 = S.calibrate(p2, wpm)
        t3 = sum(d for d, c, bs in p3)

        agg['syntax deceleration (2.1x / 1.5x / 1.4x + 125ms shift)'] += (t1 - t0)
        agg['line transitions + indent trim (400-1500ms per newline)'] += (t2 - t1)
        agg['WPM calibration (only ever subtracts)'] += (t3 - t2)

        hid = sum(15 + (104 if (code_mode and c in '{([') else 0)
                  for d, c, bs in p3 if not bs and c is not None)
        hid += sum(15 for d, c, bs in p3 if bs)
        agg['HID report overhead (15ms gap, 80ms bracket wait)'] += hid
        totals.append(t3 + hid)

    total = statistics.mean(totals)
    print(f"  target {wpm} WPM | code_mode={code_mode} | mean plan duration {total/1000:.1f}s "
          f"| effective {(len(text)/5.0)/(total/60000.0):.0f} WPM")
    for k, v in sorted(agg.items(), key=lambda x: -abs(x[1])):
        ms = v / trials
        print(f"    {ms/1000:+7.2f}s  {ms/total:+6.1%}  {k}")
    print()


if __name__ == "__main__":
    random.seed(23)
    print("=== PROSE ===")
    budget(S.PROSE, 60, False)
    budget(S.PROSE, 150, False)
    print("=== JAVA (code editor mode) ===")
    budget(S.CODE, 60, True)
    budget(S.CODE, 150, True)
