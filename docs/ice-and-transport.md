# ICE and Transport

This document covers ICE candidate gathering, STUN/TURN servers, TCP transport, and how to configure all of it through the Java API.

## What is ICE?

ICE (Interactive Connectivity Establishment) is the protocol that finds the best path between two WebRTC peers. It gathers candidate addresses (local, server-reflexive, relay), pairs them up, and runs connectivity checks to find which pairs actually work.

The library handles ICE automatically. Your job is to configure which candidate types to gather and which servers to use.

## Candidate Types

There are three types of ICE candidates:

**Host candidates** are local network addresses. They work when both peers are on the same LAN or when one peer has a public IP. Always available, no server needed.

**Server-reflexive (srflx) candidates** come from STUN servers. A STUN server tells you what your public IP and port look like from the outside. This helps when you're behind a NAT but not a symmetric one.

**Relay candidates** come from TURN servers. A TURN server relays traffic between peers when direct connection isn't possible (both behind symmetric NATs, restrictive firewalls, etc.). This is the most reliable but most expensive option.

## STUN Servers

Add a STUN server to get server-reflexive candidates:

```java
Configuration cfg = Configuration.create();
cfg.addIceServer("stun:stun.l.google.com:19302");
```

The library supports `stun:` and `stuns:` (TLS) URL schemes. STUN servers require no authentication.

## TURN Servers

Add a TURN server with credentials for relay candidates:

```java
Configuration cfg = Configuration.create();
cfg.addIceServer("turn:127.0.0.1:3478?transport=udp", "user", "pass");
```

TURN servers support `turn:` (UDP), `turns:` (TCP/TLS), and transport parameters. You need valid credentials.

## TCP Transport

By default, the library uses UDP for ICE. You can enable TCP transport:

```java
// TCP-only mode
cfg.useTcpOnly("127.0.0.1:0", DtlsRole.AUTO);

// Both UDP and TCP
cfg.setTransport(
    List.of("0.0.0.0:0"),   // UDP addresses
    List.of("0.0.0.0:0"),   // TCP addresses
    DtlsRole.AUTO.getValue(),
    NetworkType.UDP.getValue() | NetworkType.TCP.getValue()
);
```

TCP ICE candidates use `tcptype active`, `tcptype passive`, or `tcptype so` suffixes in the SDP. The library serializes these automatically when the `on_ice_candidate` callback fires.

### TCP Modes

- **Active**: The peer initiates the TCP connection (outbound).
- **Passive**: The peer accepts incoming TCP connections.
- **Simultaneous Open**: Both peers try to connect at the same time.

## DTLS Role

The DTLS role determines who acts as the DTLS client and server during the handshake:

```java
cfg.setTransport(null, null, DtlsRole.AUTO.getValue(), 0);   // let the stack decide
cfg.setTransport(null, null, DtlsRole.CLIENT.getValue(), 0);  // force client role
cfg.setTransport(null, null, DtlsRole.SERVER.getValue(), 0);  // force server role
```

In most cases, `AUTO` is the right choice. The library negotiates the DTLS role during SDP exchange.

## Network Types

You can restrict which network types are used:

```java
// UDP only
cfg.useNetworkTypes(NetworkType.UDP.getValue());

// TCP only
cfg.useNetworkTypes(NetworkType.TCP.getValue());

// Both (default if not specified)
cfg.useNetworkTypes(NetworkType.UDP.getValue() | NetworkType.TCP.getValue());
```

## Trickle ICE

See [Trickle ICE](trickle-ice.md) for details on sending candidates as they are gathered rather than waiting for all candidates to be collected.

## Example: Full ICE Configuration

```java
WebRtc.initialize();

Configuration cfg = Configuration.create();

// STUN for server-reflexive candidates
cfg.addIceServer("stun:stun.l.google.com:19302");

// TURN for relay fallback
cfg.addIceServer("turn:turn.example.com:3478?transport=udp",
                 "myuser", "mypassword");

// Restrict to port range 10000-20000
cfg.setPortRange(10000, 20000);

// Disable relay candidates (use only host + srflx)
// cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_RELAY);

PeerConnection pc = PeerConnection.create(cfg, observer);
```

## Reference: TrickleIceDemo modes

The `TrickleIceDemo` in `demo-code/` demonstrates all configuration combinations:

| Mode | ICE Servers | Candidates | Use Case |
|------|------------|------------|----------|
| `host` | None | Host only | Same LAN, no NAT |
| `srflx` | STUN | Host + srflx | Behind NAT with cone mapping |
| `relay` | TURN | Relay only | Symmetric NAT or privacy |
| `combined` | STUN + TURN | All types | Production (try all, let ICE pick best) |
| `flags` | STUN | Host + srflx | With port range and DISABLE_RELAY flag |
