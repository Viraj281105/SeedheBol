# `packages/l1_interference` — L1-to-L2 Phonetic Confusion & GOP Scoring

> **Diagnostic Assessment Engine**: Evaluates user speech against L1-specific phonological confusion sets and calculates Goodness-of-Pronunciation (GOP) scores.

---

## 📐 Goodness-of-Pronunciation (GOP) Algorithm

Given acoustic frames $X = (x_1, x_2, \dots, x_T)$ and target phoneme sequence $S = (s_1, s_2, \dots, s_N)$ with forced-alignment boundaries $(t_s, t_e)$ for phoneme $s_n$:

$$\text{GOP}(s_n) = \frac{1}{t_e - t_s + 1} \sum_{t=t_s}^{t_e} \log \left( \frac{P(s_n | x_t)}{\max_{q \in Q} P(q | x_t)} \right)$$

Where $Q$ is the complete set of Indic acoustic phonemes. A lower negative GOP score indicates severe pronunciation degradation.

---

## 🎯 L1-Interference Confusion Matrices

A speaker's native phonology systematically contaminates their acquisition of a second language:

| Source L1 | Target L2 | Expected Substitution Error | Physical Root Cause | Diagnostic Feedback |
|---|---|---|---|---|
| **Bhojpuri / Hindi** | **Tamil** | Retroflex $/\text{ʈ}/$ (ட) $\to$ Dental $/\text{t̪}/$ (த) | Lack of tongue curling back against hard palate | *"Your tongue hit your front teeth instead of curling back to the roof of your mouth."* |
| **Bhojpuri / Hindi** | **Tamil** | Retroflex Approximant $/\text{ɻ}/$ (ழ) $\to$ Flap $/\text{ɾ}/$ (ர) | Over-voicing as Hindi *ra* | *"Curl tongue further back without tapping the roof."* |
| **Odia** | **Malayalam** | Agglutinated Nasal Geminates $\to$ Glottalized stops | Missing nasal resonance | *"Hold the nasal tone through the consonant transition."* |
| **Hindi** | **Kannada** | Short / Long Vowel Contrast (ಅ vs ಆ) | Flattening vowel length | *"Hold the vowel sound twice as long."* |
