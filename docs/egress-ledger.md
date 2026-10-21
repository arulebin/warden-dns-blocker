# AI egress ledger

Warden can record which installed apps contact third-party AI providers (OpenAI,
Anthropic, Gemini, …) and block a given app from a given provider. It is off by
default and needs Android 10+. Turn it on from the **AI** tab.

## How it works

```
App → tun → Warden read loop
      ├─ DNS (UDP 53)  → existing filter (blocklists, SafeSearch, bypass) → upstream
      └─ everything else, full-tunnel mode only:
           UDP 443 (QUIC)         → held back, so apps fall back to TCP
           TCP ClientHello to 443 → EgressInspector: SNI → provider → owning app → ledger
                                    DROP → RST to the app + RST to the stack
           the rest               → SEQPACKET pair → hev-socks5-tunnel (lwIP)
                                    → Socks5Server (loopback) → protected sockets → network
```

- **Capture.** Only the TLS ClientHello's SNI is read. No certificate is installed and
  no traffic is decrypted. Payloads are never stored: the ledger is one row per
  (app, provider) with hosts, counts and first/last-seen times.
- **Attribution.** `ConnectivityManager.getConnectionOwnerUid` (API 29+) on the app's
  original 5-tuple. That is why Warden pumps packets to the stack itself instead of
  handing it the tun fd: a SOCKS session no longer carries the app's source port.
- **Enforcement.** A blocked (app, provider) pair has its connection reset at the
  ClientHello. In DNS-only mode the ledger records lookups instead and answers a
  blocked pair with NXDOMAIN, but only when the lookup can be attributed.
- **Persistence.** Rows are kept in `egress_ledger.db` (platform SQLite), saved within 5 s
  of a change (bursts coalesce into one write) and when the VPN stops. Blocks are kept in the `warden_egress` prefs. The
  AI tab exports CSV.

Code: `ledger/` holds the platform-free core and its JVM tests. In `app/`: `Egress`,
`FullTunnel`, `Socks5Server`, `TProxy`, `LedgerStore`, `UidAttributor` and `LedgerScreen`.

## Building

The stack is `app/src/main/jni/hev-socks5-tunnel`, a git submodule pinned to 2.19.1,
built with ndk-build (NDK 28.2). Its headers are **git symlinks**. On Windows, enable
Developer Mode and clone with:

```
git clone -c core.symlinks=true --recurse-submodules <repo>
```

Without real symlinks the native build fails with "unknown type name" errors.

## Known limits

- **ECH** hides the SNI, so those connections are not attributed.
- **Apps that proxy AI calls through their own backend** look first-party, and are not
  recorded by design.
- **QUIC** is held back for all apps while recording is on, not only for AI hosts.
  Apps fall back to TCP, which can add a short delay on the first connection.
- **The provider hostname list** is seed list v0. It must be re-verified against each
  provider's API docs before any corpus collection.
- **Not built yet:** the policy/Data Safety disclosure pipeline in
  `ledger/…/disclosure` (stage 1 scanner and verdict grid) is not connected to any UI or
  fetcher, and the LLM-assisted stage 2 doesn't exist.
