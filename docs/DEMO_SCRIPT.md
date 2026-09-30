# EdgeMind — Demo Recording Script (~2.5 min)

**Setup before recording**
- Fresh install of the release APK (the P-101 dataset loads automatically on first launch).
- Open the app once, wait ~5 s, then force-close and reopen, so the 8 records are indexed.
- Turn on **Airplane mode** before recording. Everything shown works offline.
- Screen recorder on, notifications silenced, dark theme.

---

### 0:00–0:15: Hook
**Screen:** App opens on Ask.
**VO:** "Field technicians don't lose signal on purpose. EdgeMind keeps the plant's memory on the device, so it can search it, reason over it, and cite it with no connection at all."

### 0:15–0:35: Offline proof + memory
**Screen:** Pull down the status bar and show that Airplane mode is on. Switch to **Memory**: scroll the 8 P-101 records (leakage, vibration, seal replacement, INC-1042 incident, procedures, cavitation).
**VO:** "We're fully offline. This is pump P-101's maintenance history: eight real records, stored and vector-indexed locally in Qdrant Edge."

### 0:35–1:00: Q1, why it keeps failing
**Screen:** Ask: `Why does P-101 keep experiencing mechanical seal failures?`
**VO:** "Ask a real question. The answer is built only from the stored records, and every sentence is cited: the investigation, the replacement, the repeated failure."
**Action:** Tap one citation to open the source record, then go back.

### 1:00–1:20: Q2, exact identifier
**Screen:** Ask: `What incident was associated with the repeated seal failure?`
**VO:** "Exact identifiers matter on a plant floor. It finds INC-1042, even though the question never mentioned it."

### 1:20–1:40: Q3, exact measurement
**Screen:** Ask: `What suction pressure was recorded during the P-101 observations?`
**VO:** "Measurements come back verbatim: approximately 2.1 bar, from two separate observations."

### 1:40–2:05: Q4, no hallucination (the key moment)
**Screen:** Ask: `What is the confirmed root cause of the P-101 seal failures?`
**VO:** "Here's what matters most. There is no confirmed root cause in these records, and EdgeMind says so: root cause remains unconfirmed. It surfaces unstable suction and possible cavitation as contributing conditions, without inventing a diagnosis."
**Action:** Pause 2 s on the "Root cause remains unconfirmed" sentence.

### 2:05–2:20: Procedure
**Screen:** Ask: `What should a technician inspect before replacing the seal again?`
**VO:** "And it turns history into action: the inspection procedure, straight from the plant's own documents."

### 2:20–2:35: Close
**Screen:** Back to Memory. Optionally show a record's sync-policy chip.
**VO:** "On-device memory, offline retrieval, grounded answers, and privacy policy on what ever leaves the device. EdgeMind: AI that works where the work is."

---

**Notes for the recorder**
- Type questions at a natural pace. Keep each answer on screen for at least 3 s before scrolling.
- Don't ask questions outside the dataset during recording. With no evidence the app answers "insufficient evidence" by design.
- Record in one take per section so they can be cut together.
