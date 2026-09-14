# SofaPower Hub

Windows companion for SofaPower. It detects physical Ethernet adapters, shows MAC/IP/gateway, checks Wake-on-LAN settings, and can apply the Windows-side WoL configuration through an elevated PowerShell session.

## One-click setup

- Enables Wake on Magic Packet where the driver exposes it.
- Allows the network adapter to wake the PC via `powercfg` when supported.
- Optionally disables Windows Fast Startup.
- Restarts the selected network adapter after applying settings.

BIOS/UEFI settings are intentionally not changed. The app reminds the user to enable Wake on LAN / PCIe wake and disable ErP when needed.

## Updates

The app checks GitHub releases in `Horizongit228/SofaPower` and only considers release tags beginning with `hub-v`, for example `hub-v1.0.0`.
