# 03 — Goodness-of-Pronunciation (GOP) & L1 Phonological Diagnostics

## 1. Mathematical Formulation of GOP

The Goodness-of-Pronunciation (GOP) algorithm quantifies how closely a speaker's acoustic realization matches a target phoneme $p$, normalized across all competing acoustic possibilities.

Given the phoneme segment spanning frame indices $[t_{start}, t_{end}]$:

$$\text{GOP}(p) = \frac{1}{t_{end} - t_{start} + 1} \sum_{t=t_{start}}^{t_{end}} \log \left( \frac{P(p | \mathbf{x}_t)}{\max_{q \in \mathcal{Q}} P(q | \mathbf{x}_t)} \right)$$

Where:
- $\mathbf{x}_t$ is the acoustic observation vector at frame $t$.
- $P(p | \mathbf{x}_t)$ is the posterior probability of phoneme $p$ emitted by IndicConformer's CTC output layer.
- $\mathcal{Q}$ represents the complete acoustic inventory of Indic phonemes.

## 2. Alignment & L1 Interference Diagnosis

```
Canonical Word: "படம்" (Pa-Dam)
G2P Phonemes:   [/p/, /a/, /ɖ/, /a/, /m/]
                       │
                       ▼
CTC Alignment:  [0-80ms: /p/][80-160ms: /a/][160-240ms: /d̪/ (ERROR)][240-320ms: /a/][320-400ms: /m/]
                                                    │
                                                    ▼
Diagnostic Engine: Evaluates GOP score (-3.8) against Bhojpuri->Tamil confusion set.
Feedback: "Detected dental /d̪/ instead of retroflex /ɖ/. Curl tongue further back."
```
