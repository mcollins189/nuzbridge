# NuzBridge

Android companion for the [NuzLocke tracker](https://poke.runeshift.xyz). It reads
the running game's memory through RetroArch's network-command interface and feeds
live state — party, HP, route, battle, PC boxes — to the tracker over a local
WebSocket.

## Install

Point [Obtainium](https://github.com/ImranR98/Obtainium) at this repository. It
tracks the releases feed and offers updates on the device, so no APK ever has to
be copied off a PC.

## Requirements

- **RetroArch** with **Settings → Network → Network Commands: ON** (port 55355).
- A supported game. Profiles live in `app/src/main/assets/memory/` and are
  generated from the ROM itself, never from community lists — species, moves,
  abilities, types, items and map sections all come off the cartridge.

The running game is identified automatically by CRC32 (see
`assets/memory/_fingerprints.json`), because GBA headers cannot tell hacks apart:
Unbound, Gaia and Team Rocket Edition all report `POKEMON FIRE`/`BPRE`.

## Network access

Off by default, and the bridge binds `127.0.0.1` only — the tracker runs on the
same device, and browsers exempt localhost from mixed-content blocking.

Turning **Allow network access** on binds every interface so another machine can
drive a live memory probe (`_tools/relay-probe.mjs` in the tracker repo) while
you play. Useful over a private network such as Tailscale; leave it off on
public Wi-Fi.
