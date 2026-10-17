# MISTAKES.md — Failure Log

> Prepend new entries (newest first). One entry per unexpected failure, broken contract, or user correction.
> Any failure pattern that occurs 3 times must be promoted to a permanent rule (GEMINI.md Rule 10).

## 2026-10-17 — Hardcoded Port 5303 in CottenRouter Co-existence Collision with StormDNS
- **Date & Incident:** User pointed out that hardcoding port `5303` in `scripts/install.sh` and documentation causes a conflict because CottenRouter assigns port `5303` to the StormDNS service.
- **Root Cause:** Assumed port `5303` was arbitrary and safe without checking standard ports allocated to other DNS tunneling services (StormDNS = `5303`, CottenDNS = `5301`, SlipGate = `5302`) in the CottenRouter ecosystem.
- **Impact:** Potential port conflict, binding error, and service interruption if StormDNS is active on the server.
- **Preventive Invariant:** Never hardcode backend ports in shared reverse proxy ecosystems; dynamically probe active sockets (`ss -lun -ltn`) and reverse proxy configurations (`/etc/cottenrouter/config.json`) to select the first free `53xx` port, with interactive override support.
