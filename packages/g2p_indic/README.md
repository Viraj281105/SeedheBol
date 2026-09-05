# `packages/g2p_indic` — Indic Grapheme-to-Phoneme & Schwa Deletion Engine

> **Linguistic Front-End**: Converts Indian language text/abugida orthography into canonical phonemic sequences for alignment and pronunciation assessment.

---

## 🔬 Core Linguistic Capabilities

### 1. Near-Phonemic Abugida Decomposition
Indian abugida scripts (Devanagari, Tamil, Telugu, Kannada, Malayalam, Odia) map almost directly from graphemes to phonemes. `g2p_indic` decomposes complex conjuncts (e.g., क्ष, ज्ञ, த்ர) into standardized International Phonetic Alphabet (IPA) tokens.

### 2. Hindi & Indo-Aryan Schwa-Deletion Rule Engine
In Devanagari, characters have an inherent schwa vowel ($/\partial/$) unless suppressed by a *halant/virama*. In spoken Hindi/Bhojpuri, word-final and certain medial schwas are deleted:
- Orthography: **क-म-ल** ($/k \partial m \partial l \partial/$)
- Spoken Output: **कमल** ($/k \partial m \partial l/$)

`g2p_indic` executes rigorous context-sensitive phonotactic rules to prevent phoneme alignment drift during forced alignment.

### 3. Dravidian Script Support (Tamil / Kannada / Malayalam)
Handles Dravidian phonetic rules, including Tamil voiceless-to-voiced stop assimilation in intervocalic and post-nasal positions (e.g., படம் pronounced *padam*, தம்பி pronounced *thambi*).
