# `packages/dialogue_engine` — Situational Branching Dialogue State Machine

> **Deterministic Low-Latency Dialogue Manager**: Replaces heavy, hallucination-prone on-device SLMs with pre-compiled situational dialogue graphs.

---

## 💡 Why a Deterministic Graph Beats an SLM on Mobile

1. **Zero Hallucination**: Migrant workers require precise domain language (e.g., legal wage rates, hospital consent). A 1–2B SLM frequently produces broken grammar and unnatural register in Dravidian languages.
2. **Instant Response Latency**: Graph traversal is instantaneous ($< 5\text{ms}$), enabling overall ASR $\to$ Dialogue $\to$ TTS response times under **800ms**.
3. **RAM & Thermal Footprint**: Eliminates 1.5GB–2.0GB of autoregressive KV-cache RAM requirements, keeping the entire runtime footprint under **100MB**.

---

## 🌳 Dialogue Schema & AST

Situations are modeled as acyclic directed graphs containing:
- `situation_id`: Unique identifier (e.g., `tamil_construction_wage_dispute_01`).
- `prompt_node`: Persona speech prompt, audio asset URI, and register variant (Peer / Supervisor / Elder).
- `expected_branches`: Array of recognized intents with regex/fuzzy acoustic matchers.
- `fallback_reprompt`: Audio prompt if user response is unrecognized or disfluent.
