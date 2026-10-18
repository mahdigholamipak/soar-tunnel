# Tasks Ledger: Soar v1.4.0

## Authorized Test Modifications
None. All existing test invariants remain strictly immutable.

## Milestones

- [x] **Milestone 1: Privacy Shield for Locked Profiles**
  - [x] 1.1 Add domain and key redaction in Go client stdout (`cmd/soar-client/main.go`)
  - [x] 1.2 Sanitize and redact logs in Windows GUI (`windows/MainWindow.xaml.cs`) when profile `IsLocked` is active
  - [x] 1.3 Sanitize and redact logs in Android Client (`android/.../MainActivity.kt`) when profile `isLocked` is active
  - [x] 1.4 Verify that no domain names, server keys, or server endpoints appear in log outputs

- [x] **Milestone 2: Smart Real-Time Network Self-Tuning Engine (`tuner.go`)**
  - [x] 2.1 Implement `AdaptiveTuner` in `tuner.go` with dynamic RTT tracking, dial timeout clamping, and composite quality scoring
  - [x] 2.2 Wire `AdaptiveTuner` into `MultiClient` and `Client` in `cmd/soar-client/main.go` and `client.go`
  - [x] 2.3 Integrate adaptive FEC parity scaling based on real-time packet loss rates
  - [x] 2.4 Emit `[TUNER]` telemetry metrics for live dashboard reporting in Windows and Android

- [x] **Milestone 3: Resolvers Management & Bank Overhaul in Windows GUI**
  - [x] 3.1 Design new Resolvers UI layout in `windows/MainWindow.xaml`: search box, filter chips, action bar, modern resolver card item template
  - [x] 3.2 Implement filter logic (All, Active, Fast, Iran Local, Global) and search filter in `windows/MainWindow.xaml.cs`
  - [x] 3.3 Implement `Add Resolver`, `Edit Resolver`, `Delete Resolver`, `Auto-Prune Dead`, and `Export` dialogs/actions
  - [x] 3.4 Support dual view: Card List vs Raw Text Editor
  - [x] 3.5 Update Version strings to `v1.4.0`

- [x] **Milestone 4: Resolvers Management & Bank Overhaul in Android Client**
  - [x] 4.1 Update Android layout `activity_main.xml` with modern search, filter chips, action bar, and Card-based list
  - [x] 4.2 Implement resolver adapter, filter logic, and add/edit/delete dialogs in `MainActivity.kt`
  - [x] 4.3 Implement auto-pruning and import/export capabilities in Android
  - [x] 4.4 Update Android version to `1.4.0` (versionCode 14)

- [ ] **Milestone 5: Verification, Release, Deployment & Benchmark**
  - [ ] 5.1 Format Go files cleanly (`gofmt`) and check type/syntax integrity
  - [ ] 5.2 Commit, tag `v1.4.0`, push to GitHub, and monitor GitHub Actions Release build
  - [ ] 5.3 Deploy `soar-server` v1.4.0 to VPS (`95.182.101.232`) and verify service
  - [ ] 5.4 Deploy updated Windows binaries to `S:\programs\Soar gui`
  - [ ] 5.5 Execute comparative benchmark and analyze performance improvements
