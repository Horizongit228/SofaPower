# SofaPower Hub

Windows companion for SofaPower Android.

## 1.1

- Finds physical Ethernet adapters and the active one.
- Shows MAC, IPv4, gateway, Magic Packet and Fast Startup state.
- Runs a readable Wake-on-LAN readiness checklist.
- Applies Windows WoL settings through a UAC-confirmed PowerShell process.
- Generates a local QR code containing only the PC name, Ethernet MAC and local IPv4 so the Android app can pair in one scan.
- Checks Hub updates from GitHub Releases.
- Does not use accounts, analytics, advertising SDKs, cloud storage or telemetry. MAC/IP are not persisted by Hub.

BIOS/UEFI still needs to be checked manually: Wake on LAN/PCI-E enabled and ErP/EuP disabled.
