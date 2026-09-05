# `packages/shared_models` — Unified Data Models & Situation Schemas

> **Cross-Platform Domain Schemas**: Common data transfer objects, situation AST schemas, user progress profiles, and cryptographic skill attestation formats.

---

## 📋 Core Models

### 1. Situation & Exercise AST (`SituationModel`)
- `id`: String identifier
- `domain`: Domain enum (`construction`, `healthcare`, `logistics`, `domestic`, `driving`, `hospitality`)
- `corridor`: Language pair (`bhojpuri_tamil`, `odia_malayalam`, `hindi_kannada`)
- `utterances`: Array of target spoken phrases with phonetic decompositions
- `branches`: Dialogue graph transitions and fallback triggers

### 2. User Learning State (`UserProfile`)
- `l1_language`, `l2_language`, `target_domain`
- `literacy_level`: Enum (`zero_literacy`, `semi_literate`, `fluent`)
- `known_lemmas`: HashSet of mastered vocabulary IDs
- `phoneme_error_history`: Map of error frequencies by phoneme pair (e.g., `tamil_retroflex_ta -> 14 errors`)
- `spaced_repetition_queue`: Spaced repetition schedule based on speech response hesitation (ms)

### 3. Cryptographic Skill Attestation (`SignedAttestation`)
- Represents an offline, verifiable credential ("Functional Tamil: Construction Register Level 2") signed on-device via Android Keystore StrongBox (Ed25519) for employer verification.
