#!/usr/bin/env python3
"""
One-time (offline) asset generator for the two real-recording acoustic soundpacks:

  Keystroke-Datasets-main/MBPWavs/<KEY>.wav -> assets/sounds/macbook-real/   ("Real Sound Mode")
  Keystroke-Datasets-main/Zoom/<key>.wav     -> assets/sounds/macbook-zoom/  ("Zoom Recording")

Both are from the paper "A Practical Deep Learning-Based Acoustic Side Channel
Attack on Keyboards" -- MBPWavs is a phone mic set down next to the MacBook;
Zoom is the same physical typing captured through Zoom's built-in meeting
recorder (different mic path/compression, so it has its own distinct color).

Each source file is NOT a single click -- it's ~20-25s containing 25 real
keystrokes of that one key, struck at slightly different times/strengths,
with silence between them. There's no config.json timing map for either (unlike
the Mechvibes-sourced packs this project used to also ship), so this script
finds the keystrokes itself: it envelope-follows each recording, peak-picks
the strokes, and keeps several clean representative strikes per key.

WHY THE MASTERING HERE IS THE WAY IT IS
---------------------------------------
A key click is *perceptually* a transient: what makes it read as a distinct,
crisp "tick" rather than a mushy thud is its crest factor (peak-to-RMS ratio)
and how fast its tail gets out of the way before the next keystroke lands. The
source recordings are excellent on both counts -- ~20-24 dB crest, ~64 dB SNR,
click energy down 20 dB within ~55 ms.

An earlier version of this script applied 6 dB of makeup gain plus a tanh
soft-limiter to every clip to make them "louder". That is the standard trick for
sustained material and exactly the wrong one here: the limiter pinned the
transient peak while the makeup gain lifted the decay and room tone underneath
it, collapsing crest factor from ~20 dB to ~7 dB and raising the tail ~50 dB.
Every clip became a flat 178 ms block of amplified room noise with a barely
audible attack, and because 178 ms is longer than the gap between keystrokes
above ~80 WPM, those blocks overlapped into a continuous wash instead of
individually audible strokes.

So: no makeup gain, no limiting. Loudness is the playback layer's job
(AcousticTypingEngine sets per-stroke SoundPool volume); this script's job is to
deliver the cleanest, shortest, highest-crest click it can. Concretely it now
    * keeps clips to ~100 ms with a tail taper, so a stroke finishes before the
      next one starts even at 120 WPM,
    * high-passes rumble and downward-expands the residual room tone, so
      overlapping tails don't accumulate into a wash,
    * peak-normalizes only -- crest factor survives at ~18-22 dB,
    * emits several *different real strikes* per key (VARIANTS_DOWN) instead of
      one, so repeated letters in a word are never the identical waveform.

Coverage gap: both datasets only recorded 0-9 and a-z (36 keys) -- no
Space/Enter/Tab/Backspace/Shift or punctuation-key recordings, and unlike a
mechanical switch a chiclet key's release click is usually too quiet to
isolate at all from either mic. Rather than ship those keys silent, this
script derives them from real strokes of the closest acoustic/physical
analogue (see derive_missing_keys() below) -- never synthesized from scratch,
always a resampled/filtered/gained slice of an actual recorded keystroke.
This is a deliberate approximation, not an authentic per-key recording; see
the "macbook-real"/"macbook-zoom" entries in
app/src/main/java/com/wirekey/audio/SoundPackCatalog.kt.

Playback timing (how a down/up pair here lines up with the actual moment a
key is pressed during a send) is entirely TypingSessionManager/
AcousticTypingEngine's job at runtime, not this script's -- see those files'
doc comments. This script only has to produce correctly-named clips per
physical key; cadence sync is the same code path for every pack.

Requires numpy + scipy (`pip3 install numpy scipy`). Not run on the Android
device; its *output* (app/src/main/assets/sounds/macbook-{real,zoom}/) is
what ships.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, find_peaks, resample, sosfiltfilt

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET_ROOT = REPO_ROOT / "Keystroke-Datasets-main"
ASSETS_ROOT = REPO_ROOT / "app" / "src" / "main" / "assets" / "sounds"

SAMPLE_RATE = 44100

# Clip window. POST_MS is deliberately just past the source clicks' measured -20 dB
# decay point (~55 ms) rather than their -40 dB point (~200 ms): the last 140 ms of a
# fully-decayed click is room tone, and at 100 WPM keystrokes are only 120 ms apart, so
# shipping that tail guarantees every stroke is heard on top of the previous one's noise.
PRE_MS = 6.0            # captured before the detected attack, to keep the true onset
POST_MS = 95.0          # captured after the attack -- click body + natural decay, no more
FADE_IN_MS = 1.0
FADE_OUT_MS = 12.0
MIN_STROKE_GAP_MS = 150.0   # keystrokes in the source files are much further apart than this
ENVELOPE_WIN_MS = 4.0

# Tail control. From TAIL_TAPER_START_MS the clip is multiplied by an exponential
# decay that reaches silence at the clip end, on top of the natural decay -- this is
# what stops tails from accumulating when strokes overlap at high WPM.
TAIL_TAPER_START_MS = 42.0
TAIL_TAPER_DECAY = 5.5      # e-folds across the tapered region; higher = tighter click

# Downward expander applied to residual room tone (below EXPANDER_THRESH_DB relative
# to the clip's own peak). Ratio 2.0 means a sample 10 dB under the threshold comes out
# 20 dB under it -- the click itself is untouched, the noise between/under it drops away.
EXPANDER_THRESH_DB = -30.0
EXPANDER_RATIO = 2.0
EXPANDER_SMOOTH_MS = 3.0

# Both datasets were captured by a mic sitting *on the desk*, so they are dominated by
# structure-borne thump: 64-96% of each clip's energy sits below 90 Hz, and only ~0.1-0.4%
# above 2 kHz. A phone speaker reproduces almost none of that low end, so leaving it in
# costs twice over -- it is the inaudible rumble, not the click, that sets the peak the
# normalizer scales to, which pushes the part you can actually hear down to around
# -54 dBFS. High-passing here lifts the audible (>500 Hz) content of a normalized clip by
# ~28 dB on MBPWavs and ~12 dB on Zoom. That is the main reason keystrokes read as faint
# and indistinct on a handset.
#
# Applied file-wide in load_mono_44100() *before* stroke detection and normalization, not
# per-clip afterwards: filtering a ~100 ms clip in isolation both fights filter edge
# transients and, because it strips the low-frequency energy that carried the sample peak,
# relocates the clip's loudest moment to 33-71 ms -- an audible late, soft thud.
HIGHPASS_HZ = 250.0

TARGET_PEAK_DOWN = 0.95
# The release of a chiclet key is a much smaller event than the press -- roughly 12-16 dB
# down in real recordings. Shipping it near press level (as a previous revision did, at
# 0.70 peak) is what made every keystroke read as two hits rather than one.
TARGET_PEAK_UP = 0.20
UP_LOWPASS_HZ = 5200.0  # releases are duller than presses: no bright leading-edge click
UP_LEN_MS = 45.0

# Several *different real strikes* per key. Repeated letters ("ll", "ee", "tt") played
# from one sample are the single most obvious sampler tell; three strikes of differing
# force cover ordinary text without a noticeable cycle.
VARIANTS_DOWN = 3
VARIANTS_UP = 2
# Percentiles of the per-key candidate peak distribution to draw the down variants from:
# a softer, a typical, and a firmer strike of the same key.
VARIANT_PERCENTILES = (35, 60, 85)

# A candidate clip is only usable if its loudest sample really is the attack we detected,
# near the front of the window, and if that attack stands well clear of the recording's own
# noise floor. Clips failing this are what produced the audible "late thud" keys in the
# previous packs (Zoom's KeyA peaked at 165 ms, Backspace at 150 ms -- i.e. the detector had
# locked onto noise and the real stroke was not in the clip at all).
#
# The SNR reference is the *source file's* noise floor, not the clip's own pre-roll: 6 ms
# ahead of a click's loudest sample the click is already ringing, so a pre-roll comparison
# measures the attack's leading edge against its own peak (~4 dB even for pristine strikes)
# and rejects everything.
MAX_ATTACK_OFFSET_MS = 14.0
MIN_ATTACK_SNR_DB = 20.0
NOISE_FLOOR_PERCENTILE = 10

# Every real key recorded in either dataset: digits 1-9,0 and letters a-z.
DATASET_KEYS = [str(d) for d in range(1, 10)] + ["0"] + [chr(c) for c in range(ord("a"), ord("z") + 1)]


@dataclass(frozen=True)
class DatasetSource:
    name: str            # for log messages
    src_dir: Path
    dest_pack_id: str    # -> assets/sounds/<dest_pack_id>/, must match SoundPackCatalog's id
    letters_uppercase: bool   # MBPWavs files are "A.wav".."Z.wav"; Zoom's are "a.wav".."z.wav"


SOURCES = [
    DatasetSource("MBPWavs (Real Sound Mode)", DATASET_ROOT / "MBPWavs", "macbook-real", letters_uppercase=True),
    DatasetSource("Zoom (Zoom Recording)", DATASET_ROOT / "Zoom", "macbook-zoom", letters_uppercase=False),
]


def dataset_filename(key: str, letters_uppercase: bool) -> str:
    if key.isdigit():
        return f"{key}.wav"
    return f"{key.upper() if letters_uppercase else key}.wav"


def physical_key_name(key: str) -> str:
    return f"Digit{key}" if key.isdigit() else f"Key{key.upper()}"


def load_mono_44100(path: Path) -> np.ndarray:
    sr, data = wavfile.read(path)
    if np.issubdtype(data.dtype, np.integer):
        data = data.astype(np.float32) / float(np.iinfo(data.dtype).max)
    else:
        data = data.astype(np.float32)
    if data.ndim == 2:
        data = data.mean(axis=1)
    if sr != SAMPLE_RATE:
        data = resample(data, int(round(len(data) * SAMPLE_RATE / sr))).astype(np.float32)
    # Drop the desk rumble before anything else looks at this audio, so that stroke
    # detection, the attack-position check and normalization all operate on the band that
    # will actually be heard. See HIGHPASS_HZ.
    data = high_pass(data, HIGHPASS_HZ)
    # The Zoom set peaks around 0.02 full scale (Zoom's own AGC/compression left it very
    # quiet); MBPWavs peaks near 0.7. Bring every source file to the same working level
    # before detection so one set of relative thresholds applies to both.
    peak = float(np.max(np.abs(data)))
    if peak > 1e-9:
        data = data * (0.9 / peak)
    return data.astype(np.float32)


def envelope(x: np.ndarray, win_samples: int) -> np.ndarray:
    power = np.convolve(x.astype(np.float64) ** 2, np.ones(win_samples) / win_samples, mode="same")
    return np.sqrt(power)


def find_strokes(x: np.ndarray) -> list[int]:
    """Returns sample indices of each detected keystroke's attack peak."""
    win = max(1, int(ENVELOPE_WIN_MS / 1000 * SAMPLE_RATE))
    env = envelope(x, win)
    min_distance = int(MIN_STROKE_GAP_MS / 1000 * SAMPLE_RATE)

    # The source recordings vary a lot in level (phone/Zoom mic, no gain
    # normalization), so a fixed absolute threshold doesn't work across all
    # files -- scale it off this file's own envelope distribution instead.
    # Backs off if that finds too few.
    for percentile, mult in ((92, 0.35), (85, 0.25), (75, 0.15)):
        thresh = np.percentile(env, percentile) * mult
        peaks, _ = find_peaks(env, height=thresh, distance=min_distance)
        if len(peaks) >= 5:
            break

    # The envelope's convolution window smooths/lags the true attack sample by
    # roughly half the window; re-snap each peak to the nearest true |x| local max.
    refined = []
    snap_radius = win
    for p in peaks:
        lo, hi = max(0, p - snap_radius), min(len(x), p + snap_radius)
        refined.append(lo + int(np.argmax(np.abs(x[lo:hi]))))
    return refined


def extract_clip(x: np.ndarray, attack_sample: int) -> np.ndarray | None:
    pre = int(PRE_MS / 1000 * SAMPLE_RATE)
    post = int(POST_MS / 1000 * SAMPLE_RATE)
    start, end = attack_sample - pre, attack_sample + post
    if start < 0 or end > len(x):
        return None
    return x[start:end].copy()


def noise_floor_of(x: np.ndarray) -> float:
    """The recording's between-strokes level: strokes are brief and sparse in these files,
    so a low percentile of the envelope is the room/mic noise."""
    win = max(1, int(ENVELOPE_WIN_MS / 1000 * SAMPLE_RATE))
    return float(np.percentile(envelope(x, win), NOISE_FLOOR_PERCENTILE)) + 1e-9


def is_clean_attack(clip: np.ndarray, noise_floor: float) -> bool:
    """Rejects clips whose loudest moment isn't the attack at the front of the window,
    or that sit too close to the noise floor to be a real strike. See
    MAX_ATTACK_OFFSET_MS's comment for the bug this guards against."""
    peak_idx = int(np.argmax(np.abs(clip)))
    if peak_idx > int(MAX_ATTACK_OFFSET_MS / 1000 * SAMPLE_RATE):
        return False
    peak = float(np.max(np.abs(clip)))
    if peak < 1e-5:
        return False
    return 20 * np.log10(peak / noise_floor) >= MIN_ATTACK_SNR_DB


def _filtfilt(clip: np.ndarray, cutoff_hz: float, btype: str) -> np.ndarray:
    """Zero-phase filter in second-order-section form.

    Deliberately sos + sosfiltfilt rather than (b, a) + filtfilt: the 90 Hz high-pass runs
    at a normalized cutoff of 0.004, where the transfer-function form is numerically
    ill-conditioned. On these ~100 ms clips that produced large boundary transients and
    displaced the loudest sample from the 6 ms attack out to 33-43 ms -- clips that then
    played back as a late, soft thud instead of a click.
    """
    sos = butter(2, cutoff_hz / (SAMPLE_RATE / 2), btype=btype, output="sos")
    # padlen bounded by clip length: sosfiltfilt raises if the default padding exceeds it.
    padlen = min(3 * (sos.shape[0] * 2), len(clip) - 1)
    return sosfiltfilt(sos, clip, padlen=max(0, padlen)).astype(np.float32)


def high_pass(clip: np.ndarray, cutoff_hz: float) -> np.ndarray:
    return _filtfilt(clip, cutoff_hz, "high")


def low_pass(clip: np.ndarray, cutoff_hz: float) -> np.ndarray:
    return _filtfilt(clip, cutoff_hz, "low")


def expand_noise_floor(clip: np.ndarray) -> np.ndarray:
    """Downward expansion of everything under EXPANDER_THRESH_DB (relative to this clip's
    own peak). Leaves the transient completely untouched -- it's far above threshold --
    while pushing room tone and the decayed tail toward silence, so overlapping strokes
    don't stack their noise floors into a continuous hiss."""
    if float(np.max(np.abs(clip))) < 1e-9:
        return clip
    win = max(1, int(EXPANDER_SMOOTH_MS / 1000 * SAMPLE_RATE))
    env = envelope(clip, win) + 1e-9
    # Threshold is relative to the peak of the *envelope*, not of the raw samples. Against
    # the sample peak, a sharp transient -- whose 3 ms RMS is far below its single-sample
    # peak -- computes a sub-unity gain and gets attenuated *more* than the broader, duller
    # body just after it, which is exactly inverted. That silently moved the loudest moment
    # of 23 clips in the macbook-real pack out to 34-45 ms, i.e. audibly late and soft.
    thresh = float(np.max(env)) * (10 ** (EXPANDER_THRESH_DB / 20))
    # gain = (env/thresh)^(ratio-1), capped at 1: unity at and above the threshold (so the
    # whole attack passes untouched), falling away increasingly steeply below it.
    gain = np.minimum(1.0, (env / thresh) ** (EXPANDER_RATIO - 1.0))
    # Smooth the gain curve itself so it can't modulate fast enough to add zipper noise.
    gain = np.convolve(gain, np.ones(win) / win, mode="same")
    gain = np.minimum(1.0, gain)
    return (clip * gain).astype(np.float32)


def taper_tail(clip: np.ndarray) -> np.ndarray:
    """Forces the clip to reach silence by its end, on top of the click's natural decay.
    Without this a stroke is still sounding when the next one lands above ~80 WPM."""
    clip = clip.copy()
    start = int(TAIL_TAPER_START_MS / 1000 * SAMPLE_RATE)
    if start >= len(clip):
        return clip
    n = len(clip) - start
    clip[start:] *= np.exp(-TAIL_TAPER_DECAY * np.linspace(0, 1, n)).astype(np.float32)
    return clip


def apply_fades(clip: np.ndarray) -> np.ndarray:
    clip = clip.copy()
    fade_in_n = min(int(FADE_IN_MS / 1000 * SAMPLE_RATE), len(clip))
    fade_out_n = min(int(FADE_OUT_MS / 1000 * SAMPLE_RATE), len(clip))
    if fade_in_n > 0:
        clip[:fade_in_n] *= np.linspace(0, 1, fade_in_n)
    if fade_out_n > 0:
        clip[-fade_out_n:] *= np.linspace(1, 0, fade_out_n)
    return clip


def normalize_peak(clip: np.ndarray, target_peak: float) -> np.ndarray:
    peak = np.max(np.abs(clip))
    if peak < 1e-6:
        return clip
    return (clip * (target_peak / peak)).astype(np.float32)


def master_down(raw_clip: np.ndarray) -> np.ndarray:
    """Press click: room tone expanded away, tail tapered, peak-normalized. Rumble is
    already gone -- load_mono_44100 high-passes the whole source file up front.
    Deliberately no compression/limiting -- see the module docstring."""
    clip = expand_noise_floor(raw_clip)
    clip = taper_tail(clip)
    clip = apply_fades(clip)
    return normalize_peak(clip, TARGET_PEAK_DOWN)


def master_up(raw_clip: np.ndarray) -> np.ndarray:
    """Release click: a short, dull, quiet event. Built from a *different* strike than the
    press it accompanies (see build_key_clips) so it can't phase-align into sounding like
    one doubled hit, and kept ~14 dB down so it reads as the key coming back up."""
    up_len = int(UP_LEN_MS / 1000 * SAMPLE_RATE)
    clip = low_pass(raw_clip[:up_len], UP_LOWPASS_HZ)
    clip = expand_noise_floor(clip)
    clip = apply_fades(clip)
    return normalize_peak(clip, TARGET_PEAK_UP)


def sharpness(clip: np.ndarray) -> float:
    """Peak^2 / total-energy -- high for a spiky transient (good Backspace source),
    low for a broad/decaying thud (good Space source)."""
    peak = np.max(np.abs(clip))
    energy = float(np.sum(clip.astype(np.float64) ** 2)) + 1e-9
    return float(peak * peak) / energy


def pitch_shift(clip: np.ndarray, factor: float) -> np.ndarray:
    """factor > 1 => higher pitch & shorter (like faster tape playback)."""
    n_new = max(1, int(round(len(clip) / factor)))
    return resample(clip, n_new).astype(np.float32)


def to_int16(clip: np.ndarray) -> np.ndarray:
    return np.clip(clip * 32767.0, -32768, 32767).astype(np.int16)


def write_wav(path: Path, clip: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(path, SAMPLE_RATE, to_int16(clip))


def pick_variants(candidates: list[np.ndarray], count: int) -> list[np.ndarray]:
    """Picks [count] *distinct* strikes spanning VARIANT_PERCENTILES of the key's strike-force
    distribution -- a soft, a typical and a firm hit of the same key rather than [count]
    copies of the same one. Falls back to cycling what's available if a key yielded few
    clean strokes."""
    peaks = np.array([np.max(np.abs(c)) for c in candidates])
    chosen: list[int] = []
    for pct in VARIANT_PERCENTILES[:count]:
        target = np.percentile(peaks, pct)
        order = np.argsort(np.abs(peaks - target))
        pick = next((int(i) for i in order if int(i) not in chosen), int(order[0]))
        chosen.append(pick)
    while len(chosen) < count:
        chosen.append(chosen[len(chosen) % max(1, len(chosen))])
    return [candidates[i] for i in chosen[:count]]


def build_key_clips(candidates: list[np.ndarray]) -> tuple[list[np.ndarray], list[np.ndarray]]:
    """Returns (down variants, up variants) for one key. The up variants are drawn from the
    *softest* strikes available and mastered far quieter -- the datasets contain no isolable
    chiclet release click, so the closest honest stand-in is a real but gentle strike of the
    same key, not a slice of the very press it plays alongside."""
    downs = [master_down(c) for c in pick_variants(candidates, VARIANTS_DOWN)]

    peaks = np.array([np.max(np.abs(c)) for c in candidates])
    softest = [candidates[i] for i in np.argsort(peaks)[:max(VARIANTS_UP, 1)]]
    ups = [master_up(softest[i % len(softest)]) for i in range(VARIANTS_UP)]
    return downs, ups


def write_key(dest_dir: Path, name: str, downs: list[np.ndarray], ups: list[np.ndarray]) -> int:
    for i, clip in enumerate(downs):
        write_wav(dest_dir / f"{name}_down_{i}.wav", clip)
    for i, clip in enumerate(ups):
        write_wav(dest_dir / f"{name}_up_{i}.wav", clip)
    return len(downs) + len(ups)


def isolate_candidate_strokes(source: DatasetSource) -> dict[str, list[np.ndarray]]:
    """All clean strokes per key, not just one -- build_key_clips picks its variants from
    these, and derive_missing_keys borrows from them too."""
    candidates_by_key: dict[str, list[np.ndarray]] = {}
    for key in DATASET_KEYS:
        src_path = source.src_dir / dataset_filename(key, source.letters_uppercase)
        if not src_path.exists():
            print(f"  [skip] {key}: {src_path.name} not found")
            continue

        audio = load_mono_44100(src_path)
        attacks = find_strokes(audio)
        floor = noise_floor_of(audio)
        raw = [c for a in attacks if (c := extract_clip(audio, a)) is not None]
        clean = [c for c in raw if is_clean_attack(c, floor)]
        if not clean:
            print(f"  [warn] {key}: no clean strokes isolated ({len(raw)} rejected)")
            continue

        candidates_by_key[key] = clean
        rejected = len(raw) - len(clean)
        note = f", {rejected} rejected" if rejected else ""
        print(f"  [ok] {key} ({len(clean)} clean strokes{note})")
    return candidates_by_key


def derive_missing_keys(dest_dir: Path, candidates_by_key: dict[str, list[np.ndarray]]) -> int:
    """Space/Enter/Tab/Backspace/Shift + all punctuation have no recording in
    either dataset -- derive each from the real letter/digit strokes that are the
    closest acoustic or physical-position analogue. See module docstring."""
    letters_only = {k: v for k, v in candidates_by_key.items() if k.isalpha()}
    if not letters_only:
        letters_only = candidates_by_key
    representative = {k: v[len(v) // 2] for k, v in letters_only.items()}

    sharpness_by_letter = {k: sharpness(v) for k, v in representative.items()}
    ranked_sharp = sorted(sharpness_by_letter, key=sharpness_by_letter.get, reverse=True)
    ranked_dull = list(reversed(ranked_sharp))

    peaks_by_letter = {k: float(np.max(np.abs(v))) for k, v in representative.items()}
    median_peak = float(np.median(list(peaks_by_letter.values())))
    shift_source = min(peaks_by_letter, key=lambda k: abs(peaks_by_letter[k] - median_peak))
    enter_source = max(peaks_by_letter, key=peaks_by_letter.get)
    backspace_source = ranked_sharp[0]
    tab_source = ranked_sharp[1] if len(ranked_sharp) > 1 else ranked_sharp[0]
    space_source = ranked_dull[0]

    written = 0

    def derive(name: str, source_key: str, pitch_factor: float,
               gain: float = 1.0, lowpass_hz: float | None = None) -> None:
        nonlocal written
        pool = candidates_by_key[source_key]
        prepared = []
        for clip in pool:
            c = clip.copy()
            if lowpass_hz is not None:
                c = low_pass(c, lowpass_hz)
            if pitch_factor != 1.0:
                c = pitch_shift(c, pitch_factor)
            prepared.append(c * gain)
        downs, ups = build_key_clips(prepared)
        written += write_key(dest_dir, name, downs, ups)

    # Modifier/whitespace keys are physically larger with different travel, so these
    # pitch factors are doing real work, not just de-duplicating.
    derive("Space", space_source, pitch_factor=0.90, gain=1.05, lowpass_hz=3800)
    derive("Enter", enter_source, pitch_factor=0.93)
    derive("Backspace", backspace_source, pitch_factor=1.08)
    derive("Tab", tab_source, pitch_factor=1.04, gain=0.9)
    derive("ShiftLeft", shift_source, pitch_factor=1.03, gain=0.8)

    # Punctuation: reuse the nearest physical-neighbor letter/digit's real
    # click -- on a laptop's uniform chiclet switches, timbre is dominated by
    # the shared mechanism, not key position, so this is a much smaller
    # approximation than it would be on a mechanical board. Every factor here is
    # deliberately != 1.0 and != its neighbours': at 1.0 the resample step is skipped
    # entirely and the subsequent peak-normalize cancels any `gain`, which is how the
    # previous packs ended up shipping Comma bit-identical to KeyM, Semicolon to KeyL
    # and Tab to KeyJ.
    punctuation_neighbors = {
        "Backquote": ("1", 1.02), "Minus": ("0", 0.98), "Equal": ("0", 1.05),
        "BracketLeft": ("p", 0.99), "BracketRight": ("p", 1.03), "Backslash": ("p", 0.95),
        "Semicolon": ("l", 1.02), "Quote": ("l", 1.06),
        "Comma": ("m", 1.02), "Period": ("m", 1.05), "Slash": ("m", 0.97),
    }
    for name, (source_key, pitch_factor) in punctuation_neighbors.items():
        derive(name, source_key, pitch_factor=pitch_factor)

    print(f"  Space source={space_source} Enter source={enter_source} "
          f"Backspace source={backspace_source} Tab source={tab_source} Shift source={shift_source}")
    return written


def process_source(source: DatasetSource) -> None:
    print(f"Processing {source.name} -> assets/sounds/{source.dest_pack_id}/")
    if not source.src_dir.is_dir():
        print(f"ERROR: dataset not found at {source.src_dir}", file=sys.stderr)
        raise SystemExit(1)

    dest_dir = ASSETS_ROOT / source.dest_pack_id
    dest_dir.mkdir(parents=True, exist_ok=True)
    # Clip naming changed (KeyA_down.wav -> KeyA_down_0.wav); stale files from an older
    # revision would otherwise sit in assets forever, shipped but never played.
    for stale in dest_dir.glob("*.wav"):
        stale.unlink()

    candidates_by_key = isolate_candidate_strokes(source)
    if not candidates_by_key:
        print("ERROR: nothing isolated, aborting derived-key step", file=sys.stderr)
        raise SystemExit(1)

    written = 0
    for key, candidates in candidates_by_key.items():
        downs, ups = build_key_clips(candidates)
        written += write_key(dest_dir, physical_key_name(key), downs, ups)

    recorded_keys = len(candidates_by_key)
    derived_written = derive_missing_keys(dest_dir, candidates_by_key)
    written += derived_written

    per_key = VARIANTS_DOWN + VARIANTS_UP
    print(f"Done. {written} WAV files written to {dest_dir}")
    print(f"  Real recordings: {recorded_keys} keys x {per_key} clips "
          f"({VARIANTS_DOWN} down + {VARIANTS_UP} up variants)")
    print(f"  Derived approximations: {derived_written // per_key} keys x {per_key} clips")
    print()


def main() -> int:
    for source in SOURCES:
        process_source(source)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
