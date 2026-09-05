# Content schema and exercise types

Specification for generated lesson content. Written against the engine as it
actually exists (see `docs/HANDOFF.md` for what is built and what is not), not
against an idealised one.

Every claim about what the engine can do here was checked against the real
`char_to_id` tables in `models/tts_fastpitch/<lang>_onnx/tokens.json` and the
ASR vocabularies in `models/<lang>/vocab.txt`. The constraints in §1 are
measured, not assumed.

---

## 1. Hard engine constraints the content must respect

These are properties of the shipped models. Content that violates them fails
**silently** — no exception, no warning, just wrong output.

### 1.1 The TTS vocabulary is small and gapped

Characters outside a language's `char_to_id` are dropped by the tokenizer
without error. The model then says something other than what was written.

| | hi | mr | ta | te | kn | bn |
|---|---|---|---|---|---|---|
| Latin digits `0-9` | 2/10 | 2/10 | 1/10 | none | none | none |
| Devanagari digits `०-९` | none | 8/10 | — | — | — | — |
| Latin letters | none | 1/26 | none | 1/26 | none | none |
| Danda `।` | none | yes | — | — | — | yes |
| Underscore `_` | none | none | none | none | none | none |

Three rules follow, and they are not negotiable:

- **RULE N — numerals are always spelled as words.** `पाँच सौ रुपये`, never
  `500 रुपये`. Most languages cannot voice a digit at all. This matters more
  here than in a general-purpose app: wages, shift times, and quantities are
  the subject matter.
- **RULE L — no Latin characters in any TTS string.** This forbids the
  code-switched vocabulary migrant workers genuinely use (`site`, `duty`,
  `OK`, `cash`, `sir`). Content that sounds authentic will break TTS; content
  that survives TTS will sound artificially pure. This is a real, unresolved
  tension — flag such phrases for review rather than silently rewriting them.
- **RULE B — no layout characters in TTS strings.** `_`, `____`, `...`, and
  markup are dropped. Blanks must be structural fields, not in-string markers.

### 1.2 The ASR emits no punctuation

Confirmed: 257 tokens per language, containing digits and Latin, containing
**no punctuation whatsoever**. Any expected answer carrying `?` `.` `,` `।`
can never be matched exactly against a spoken response.

- **RULE P — grading normalizes before comparing.** Strip punctuation and
  danda, collapse whitespace, on both sides, always.

### 1.3 There is no alignment, timing, or per-character confidence

Decoding is greedy CTC argmax producing a flat string. There is no forced
alignment, no goodness-of-pronunciation score, no per-token confidence
surfaced. The vocabulary is 257 SentencePiece **subwords**, not phonemes.

- **RULE S — no exercise may claim to score a specific sound or syllable.**
  Whole-utterance similarity is the only pronunciation signal available. If
  CTC posteriorgram alignment + GOP is built later, this rule can be lifted
  and a `focus_spans` field added.

### 1.4 Synthesis is slow cold, free warm

First synthesis of a string is ~1.5–2s on device (HiFi-GAN is 82% of that);
every repeat is a cache read. A lesson walked lazily is 30–60s of dead taps.

- **RULE A — every string requiring audio appears in a flat `audio_manifest`**
  so the whole lesson can be pre-synthesized on open in one pass.

### 1.5 OCR: four scripts, and the most important one is broken

Devanagari — Hindi *and* Marathi — does not detect at all at default
thresholds. Tamil, Telugu, Kannada detect short phrases. See `docs/HANDOFF.md`.

- **RULE O — runtime OCR grading is confidence-gated and never punitive.**
  Low OCR confidence awards credit. The user is never marked wrong because
  our detector failed.

---

## 2. Why OCR exercises earn their place

The obvious objection: this product's founding principle is that it works
**without reading**. Adding exercises about written text looks like a
contradiction. It is not, for four reasons.

**2.1 Survival recognition is not literacy.** There is a large gap between
reading fluently and knowing that *this specific shape* means "danger",
"no entry", "exit", "women", or "₹". The first takes years and the product
correctly refuses to require it. The second takes days, is achievable by
shape recognition alone, and is *physically protective*. A worker who cannot
identify the Tamil word for a hazard on a site sign is in danger that no
amount of spoken fluency fixes. Twenty shapes is a realistic three-week goal.

**2.2 It is the only exercise that touches the user's real environment.**
Every other type is generated from an authored curriculum. A camera exercise
makes the workplace itself the content source — the same philosophical move
as the ambient-audio mining feature the README already stakes the product on.
The signs at *your* site, in *your* trade, are more relevant than any content
pack we can write, and they are free.

**2.3 It is the part cloud competitors structurally cannot copy.** The
documents worth photographing are wage slips, ID papers, medicine labels,
rental agreements, employer notices. Streaming those to a server is a privacy
catastrophe and a regulatory non-starter — the identical argument the README
makes for on-device audio, and it is stronger here, because a wage slip is
more identifying than a voice clip. On-device OCR is what makes this legal to
exist.

**2.4 It closes the loop that gets people cheated.** Underpayment usually
happens in writing — a slip, a ledger, a rate posted on a board. A worker who
can photograph a wage slip and hear it read back in their own language has a
concrete defence that speaking practice alone does not provide.

### The distinction that makes this shippable today

OCR is used at **two different times**, and only one of them is blocked:

| | When OCR runs | Devanagari blocker applies? | Ships today? |
|---|---|---|---|
| `read_sign` | **Authoring time** — to label a photo bank; a human verifies | No — a human can type the label if OCR fails | **Yes, all languages** |
| `sign_hunt` | **Runtime** — on the user's own photo | Yes | Tamil/Telugu/Kannada only |

This is the useful insight: an exercise *about* real-world signage does not
require *runtime* OCR. Authoring-time OCR just accelerates building a labelled
image bank. So the signage exercise is available in Hindi and Marathi now,
while the camera-in-the-loop version waits on the detector fix.

### Honest limits

- Verification so far is on clean synthetic renders. That proves the graphs
  are wired correctly and proves **nothing** about a phone photo of a
  hand-painted sign at dusk. Real-world accuracy is unmeasured.
- A construction worker photographing site signage may attract employer
  suspicion. `sign_hunt` should always be skippable without penalty.
- An OCR error that marks a correct answer wrong is worse than having no
  exercise. Hence RULE O.

---

## 3. Final exercise types

Nine types. `dictation` from the earlier draft is **cut**: it requires writing
in the target script, which is a literacy test by definition, aimed at a
population with 10–30% illiteracy. If the intent was "hear it, then say it,"
that is `repeat_after`.

Ordered by shipping priority.

### Tier 1 — ship first (no camera, no unbuilt engine work)

| # | Type | Prompt | Response | Engine |
|---|---|---|---|---|
| 1 | `listen_choose` | L2 audio | tap one of 2–4 audio options | TTS only |
| 2 | `repeat_after` | L2 audio | speak it back | TTS + ASR |
| 3 | `say_it` | L1 audio | speak in L2 | TTS(L1+L2) + ASR |
| 4 | `build_sentence` | L2 audio | tap tiles into order | TTS per tile |

### Tier 2 — ship second

| # | Type | Prompt | Response | Engine |
|---|---|---|---|---|
| 5 | `complete_phrase` | L2 audio with a gap | tap/speak the missing part | TTS + optional ASR |
| 6 | `match_pairs` | audio pairs | tap to pair | TTS only |
| 7 | `read_sign` | photo of real signage | tap one of 2–4 audio options | authoring-time OCR |

### Tier 3 — needs work that is not done

| # | Type | Prompt | Response | Blocked on |
|---|---|---|---|---|
| 8 | `sign_hunt` | "find and photograph a sign that says X" | camera | runtime OCR; Devanagari broken |
| 9 | `respond_in_role` | L2 audio, in character | free spoken response | loose keyword grading |

### Per-type notes

**1. `listen_choose`** — replaces `mcq`. Options must be **acoustically**
distinct, not merely visually distinct: two options differing by one written
diacritic may synthesize near-identically, making the audio version
unanswerable. This is a generation constraint, and it needs a check.

**2. `repeat_after`** — replaces `pronunciation`, minus the unimplementable
per-sound scoring (RULE S). Whole-utterance similarity only.

**3. `say_it`** — replaces `translation`. The response is **spoken, never
typed** — typing requires an Indic keyboard and literacy. Needs
`accepted_variants`, because there is rarely one right way to say a thing.

**4. `build_sentence`** — replaces `sentence_construction`. Two fixes:
`accepted_orders` (plural — Hindi and Marathi permit scrambling for emphasis,
and a single canonical order marks valid sentences wrong), and explicit
tokenization, because space-splitting agglutinative Tamil/Telugu/Kannada/
Malayalam yields three enormous tiles and a trivial puzzle. Tiles should be
morpheme- or chunk-level, author-specified.

**5. `complete_phrase`** — replaces `fill_in_the_blank`. The blank is
`prefix` + `suffix` fields, never a `____` in a string (RULE B), so TTS can
speak the prefix, pause, and speak the suffix.

**6. `match_pairs`** — kept, but at **phrase** level, not word level. The
README is explicit that the atom is a situation and that "money vocabulary"
is not one; word-level matching structurally pulls back toward vocabulary
drilling.

**7. `read_sign`** — new. Photo from an authored bank; options are audio.
No runtime OCR, therefore no Devanagari blocker. Numerals in the image may be
digits; the audio options spell them as words (RULE N) — which is exactly how
a wage-slip exercise works.

**8. `sign_hunt`** — new. The environmental one. Always skippable. Never
punitive (RULE O).

**9. `respond_in_role`** — new, and the capstone that most directly serves
"the atom is a situation." Plays the other party's line (supervisor,
pharmacist, landlord); the learner responds freely. Graded on `must_contain`
keywords, not on similarity to a model answer, because there is no single
correct reply to "why are you late?".

---

## 4. Schema

### 4.1 Structural decision: three layers, not one document per pair

The earlier draft keyed `realizations` by target language and asked questions
"in the source language" without recording which source language that was.
That cannot represent the product's central claim — Bhojpuri→Tamil and
Hindi→Tamil become the same object.

The naive fix (one document per language pair) is combinatorial: 9 targets ×
~12 plausible L1s × N situations. The right factoring separates what depends
on what:

- **`realization`** — the L2 content. Depends only on the target language.
  Authored once per (situation, target).
- **`glosses`** — L1-facing text: meanings, prompts, distractors. Depends only
  on the source language. Authored once per (situation, source).
- **`exercises`** — compose the two by reference.

One document per (situation, target); L1s are added by extending `glosses`,
not by regenerating everything.

### 4.2 The document

```jsonc
{
  "schema_version": "1.0",
  "situation_id": "wages.ask_about_delayed_payment",

  // Authoring-facing only. Never shown to a learner, never synthesized.
  "situation": {
    "title_en": "Asking a supervisor about a delayed wage payment",
    "why_it_matters_en": "Underpayment is the most common exploitation...",
    "job_contexts": ["construction", "factory_floor"],
    "urgency": "week_1"
  },

  "target_language": "ta",

  // ---- L2 layer: depends only on target language ----
  "realization": {
    "text": "சம்பளம் எப்போது கிடைக்கும்",
    "transliteration": {
      "scheme": "ISO15919",
      "value": "campaḷam eppōtu kiṭaikkum"
    },
    "register": {
      "level": "polite_neutral",
      "notes_en": "Safe to use with a supervisor. The plain form ... would read as a demand."
    },
    "cultural_note_en": "Asking directly in front of other workers can...",
    "phonetic_flags": ["retroflex_ள", "geminate_க்க"],
    "variants": [
      "சம்பளம் எப்போ கிடைக்கும்"
    ]
  },

  // ---- L1 layer: one entry per supported source language ----
  "glosses": {
    "hi": {
      "meaning": "तनख़्वाह कब मिलेगी",
      "prompts": {
        "say_it": "आप यह कैसे कहेंगे — तनख़्वाह कब मिलेगी?",
        "listen_choose": "इनमें से कौन सा सही है?"
      },
      "distractors": ["पानी कहाँ मिलेगा", "काम कब ख़त्म होगा"]
    },
    "bho": { "...": "..." }
  },

  // ---- RULE A: everything needing synthesis, flat, pre-synthesizable ----
  "audio_manifest": [
    { "id": "t1", "lang": "ta", "speaker_id": 0, "text": "சம்பளம் எப்போது கிடைக்கும்" },
    { "id": "h1", "lang": "hi", "speaker_id": 0, "text": "तनख़्वाह कब मिलेगी" }
  ],

  "exercises": [ /* §4.3 */ ],

  // ---- RULE: nothing ships unvalidated ----
  "validation": {
    "tts_vocab_checked": true,
    "tts_vocab_misses": [],
    "numerals_spelled_as_words": true,
    "no_latin_in_tts_strings": true,
    "acoustic_distinctness_checked": true,
    "confidence": "high",
    "human_reviewed_by": null,
    "review_reason": null
  }
}
```

### 4.3 Exercise objects

Every exercise references audio by `audio_manifest` id, never inline text, so
pre-synthesis stays complete and cheap.

```jsonc
// 1
{ "type": "listen_choose", "id": "e1",
  "prompt_audio": "t1",
  "options": [
    { "audio": "h1", "correct": true },
    { "audio": "h2", "correct": false }
  ],
  "acoustic_min_distance": 0.35 }

// 2
{ "type": "repeat_after", "id": "e2",
  "model_audio": "t1",
  "expected_text": "சம்பளம் எப்போது கிடைக்கும்",
  "grading": {
    "method": "asr_similarity",
    "normalize": ["strip_punctuation", "strip_danda", "collapse_whitespace"],
    "accept_threshold": 0.75,
    "retry_threshold": 0.55
  } }

// 3
{ "type": "say_it", "id": "e3",
  "prompt_audio": "h1",
  "expected_text": "சம்பளம் எப்போது கிடைக்கும்",
  "accepted_variants": ["சம்பளம் எப்போ கிடைக்கும்"],
  "grading": { "method": "asr_similarity", "accept_threshold": 0.70,
               "normalize": ["strip_punctuation", "collapse_whitespace"] } }

// 4
{ "type": "build_sentence", "id": "e4",
  "prompt_audio": "t1",
  "tiles": [
    { "text": "சம்பளம்", "audio": "t1a" },
    { "text": "எப்போது", "audio": "t1b" },
    { "text": "கிடைக்கும்", "audio": "t1c" }
  ],
  "accepted_orders": [[0,1,2],[1,0,2]],
  "tokenization": "author_specified" }

// 5
{ "type": "complete_phrase", "id": "e5",
  "prefix_audio": "t1p", "suffix_audio": "t1s",   // RULE B — no "____"
  "missing_text": "எப்போது",
  "options": [ { "audio": "t1b", "correct": true },
               { "audio": "t1x", "correct": false } ],
  "response_mode": "tap_or_speak" }

// 6
{ "type": "match_pairs", "id": "e6",
  "pairs": [ { "source_audio": "h1", "target_audio": "t1" },
             { "source_audio": "h2", "target_audio": "t2" } ] }

// 7  — authoring-time OCR; no runtime OCR, works in Devanagari today
{ "type": "read_sign", "id": "e7",
  "image": "signs/ta/wage_board_01.jpg",
  "image_source": "authored_bank",
  "ocr_label": "சம்பளம்",
  "ocr_label_verified_by_human": true,
  "options": [ { "audio": "h1", "correct": true },
               { "audio": "h3", "correct": false } ] }

// 8  — runtime OCR; RULE O applies
{ "type": "sign_hunt", "id": "e8",
  "task_audio": "h4",
  "target_any_of": ["சம்பளம்", "ஊதியம்"],
  "grading": {
    "method": "ocr_contains",
    "min_ocr_confidence": 0.5,
    "on_low_confidence": "accept",
    "on_ocr_unavailable": "skip"
  },
  "skippable": true,
  "penalty_on_skip": false }

// 9
{ "type": "respond_in_role", "id": "e9",
  "role_en": "supervisor",
  "line_audio": "t9",
  "grading": {
    "method": "asr_keyword",
    "must_contain_any": ["சம்பளம்", "ஊதியம்"],
    "accept_threshold": 0.60,
    "open_ended": true
  } }
```

---

## 5. The generation pipeline must gate on these

Content is not accepted until it passes, mechanically:

1. **Vocab coverage** — every `audio_manifest` string, character by
   character, against that language's `char_to_id`. Any miss fails the
   document. This is the check that catches silent mis-speaking, and it is
   the single most valuable gate here. `scripts/verify_tts.py` already
   implements the logic.
2. **No numerals as digits** — RULE N.
3. **No Latin in TTS strings** — RULE L; flag for human review rather than
   auto-rewrite, since the underlying phrase may genuinely be code-switched.
4. **No layout characters** — RULE B.
5. **ASR reachability** — every `expected_text` should be expressible in the
   ASR's 257-token vocabulary.
6. **Acoustic distinctness** — for `listen_choose` and `complete_phrase`,
   synthesize the options and confirm they are distinguishable; two options
   that sound alike make the exercise unanswerable by ear.
7. **Human review** for anything with `confidence: "review"`, and for every
   `register` note — register errors are the ones that get someone fired.

### On thresholds

The `accept_threshold` values above are **placeholders and must be
calibrated**, not shipped as written. The only measurements taken so far
(0.947–1.000 similarity) are TTS→ASR round trips on clean synthetic audio.
Real human speech, accented, on a construction site, will score materially
lower. Calibrate against real recordings from real users before trusting
any of these numbers.

---

## 6. Open questions

- **9 targets × N sources of glosses** is still a lot of human review, and
  `confidence: "review"` implies a reviewer exists per language. Who?
- **The code-switching tension (RULE L)** has no good answer yet. Real speech
  in these languages contains English words; the TTS cannot say them. Either
  the content is less authentic, or those phrases lose audio.
- **Numerals** are spelled as words for TTS, but ASR *can* emit digits — so a
  spoken "500" may come back as `500` and fail to match `पाँच सौ`.
  Normalization needs a numeral-folding step in both directions.
- **`read_sign` needs a photo bank** that does not exist. Sourcing real
  signage photography across four scripts is a content-operations problem,
  not an engineering one, and nobody owns it yet.
