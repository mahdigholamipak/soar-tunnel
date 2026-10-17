# Tasks Ledger: Soar v1.3.0

## Authorized Test Modifications
None. All existing test invariants remain strictly immutable.

## Milestones

- [x] **Milestone 1: Protocol & Engine Enhancements (1400B EDNS0 & Adaptive AIMD Rate Control)**
  - [x] 1.1 Lift 1232B cap in `server.go` to support up to 1400B EDNS0 buffer when client allows
  - [x] 1.2 Implement adaptive AIMD rate limiting & cooldown backoff per resolver in `client.go` on SERVFAIL/Refused (RRL protection)
  - [x] 1.3 Maintain path usability and deadlock immunity (`anyUsableLocked`)
  - [x] 1.4 Format Go code cleanly with `gofmt` compliance via API

- [x] **Milestone 2: Multi-Domain Health Balancer & Smart Resolver UX**
  - [x] 2.1 Fix MultiClient dial deadlock bug where all domains backing off stalled dial indefinitely (`cmd/soar-client/main.go`)
  - [x] 2.2 Implement direct multi-line text input detection in `windows/MainWindow.xaml.cs` (auto-detect pasted IPs & auto-save to `custom_resolvers.txt`)
  - [x] 2.3 Verify default unchecked system proxy behavior in Windows GUI
  - [x] 2.4 Update version strings to `v1.3.0` across Windows GUI, Android manifest, and layout

- [ ] **Milestone 3: Verification, Release & Deployment**
  - [ ] 3.1 Commit, tag `v1.3.0`, push to GitHub, and monitor automated Release workflow
  - [ ] 3.2 Deploy `soar-server` v1.3.0 to production VPS (`95.182.101.232`) and verify `soar.service` status
  - [ ] 3.3 Deploy new `v1.3.0` binaries to `S:\programs\Soar gui`

- [ ] **Milestone 4: Live Benchmark & Performance Analysis**
  - [ ] 4.1 Execute multi-round latency, throughput (50KB & 150KB), and 4-stream concurrency benchmark
  - [ ] 4.2 Document comparative results against v1.2.0 and provide engineering feedback
