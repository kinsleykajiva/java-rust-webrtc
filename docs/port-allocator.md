# Port Allocator

The port allocator controls which UDP/TCP ports are used for ICE candidate gathering. This is useful when you need to bind to specific port ranges (firewall rules, containerized environments) or restrict which candidate types are gathered.

## Port Range

By default, the OS assigns ephemeral ports (typically 49152-65535). You can restrict this to a specific range:

```java
Configuration cfg = Configuration.create();
cfg.setPortRange(10000, 20000);
```

This rewrites any `0`-port addresses (OS-assigned) to use the minimum port as the starting point. The OS will then allocate from that range upward.

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `minPort` | int | Minimum port (inclusive). Pass 0 for unspecified. |
| `maxPort` | int | Maximum port (inclusive). Pass 0 for unspecified. |

### Rules

- If both are non-zero, `minPort` must be <= `maxPort`.
- If either is 0, the other is used as the starting port.
- Addresses that already have a non-zero port are not modified.
- Throws `IllegalArgumentException` if minPort > maxPort.

### Example

```java
// Use ports 30000-30100
cfg.setPortRange(30000, 30100);

// Use port 5000 as starting point, OS assigns upward
cfg.setPortRange(5000, 0);
```

## Allocator Flags

Allocator flags are bitmasks that control which candidate types the ICE agent gathers. Set them with `setAllocatorFlags()`:

```java
cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_RELAY);
```

Combine multiple flags with bitwise OR:

```java
cfg.setAllocatorFlags(
    PortAllocatorFlags.DISABLE_RELAY | PortAllocatorFlags.DISABLE_TCP
);
```

Pass `0` (or don't call `setAllocatorFlags`) to use all native defaults.

## Flag Reference

| Constant | Value | Effect |
|----------|-------|--------|
| `DISABLE_UDP` | 1 | No local UDP socket allocation (host candidates need TCP) |
| `DISABLE_STUN` | 2 | No STUN candidates (removes servers without credentials) |
| `DISABLE_RELAY` | 4 | No TURN relay candidates (removes servers with credentials) |
| `DISABLE_TCP` | 8 | No TCP candidates |
| `ENABLE_IPV6` | 16 | Allow IPv6 candidate gathering |
| `ENABLE_SHARED_SOCKET` | 32 | Share UDP sockets across components |
| `ENABLE_STUN_RETRANSMIT_ATTRIBUTE` | 64 | Include retransmit attribute on STUN requests |
| `DISABLE_ADAPTER_ENUMERATION` | 128 | Don't enumerate network adapters |
| `DISABLE_DEFAULT_LOCAL_CANDIDATE` | 256 | Don't generate a default local candidate |
| `DISABLE_UDP_RELAY` | 512 | Disable UDP TURN relay (TCP relay still works) |
| `DISABLE_COSTLY_NETWORKS` | 1024 | Skip cellular and expensive network interfaces |
| `ENABLE_IPV6_ON_WIFI` | 2048 | Allow IPv6 over Wi-Fi |
| `ENABLE_ANY_ADDRESS_PORTS` | 4096 | Allow binding to 0.0.0.0 ports |
| `DISABLE_LINK_LOCAL_NETWORKS` | 8192 | Skip link-local (169.254.x.x) interfaces |

## Common Configurations

### Restrict to host candidates only

```java
cfg.setAllocatorFlags(
    PortAllocatorFlags.DISABLE_STUN |
    PortAllocatorFlags.DISABLE_RELAY |
    PortAllocatorFlags.DISABLE_TCP
);
```

### Disable TURN relay

```java
cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_RELAY);
```

### Restrict to a port range on a container

```java
cfg.setPortRange(49152, 49200);
cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_STUN);
```

### IPv6 only, no relay

```java
cfg.setAllocatorFlags(
    PortAllocatorFlags.ENABLE_IPV6 |
    PortAllocatorFlags.DISABLE_RELAY |
    PortAllocatorFlags.DISABLE_UDP  // force TCP over IPv6
);
```

## How Flags Are Applied

On the Rust side, flags are applied during peer connection creation:

1. **DISABLE_UDP** (bit 0): Clears all UDP addresses from the configuration, forcing TCP-only gathering.
2. **DISABLE_TCP** (bit 3): Clears all TCP addresses, forcing UDP-only gathering.
3. **DISABLE_STUN** (bit 1): Removes ICE servers that have no credentials (STUN servers don't require auth).
4. **DISABLE_RELAY** (bit 2): Removes ICE servers that have credentials (TURN servers require auth).

Other flags are passed through to the native ICE agent for lower-level control.

## TrickleIceDemo: flags mode

The `TrickleIceDemo` includes a `flags` mode that demonstrates port range and flag usage:

```bash
java ... TrickleIceDemo flags
```

This configures:
- STUN server for server-reflexive candidates
- Port range 10000-10050
- DISABLE_RELAY flag (no TURN candidates)

The output shows candidates being gathered within the specified port range.
