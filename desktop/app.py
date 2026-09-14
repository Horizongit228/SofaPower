import base64
import ctypes
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.request
import webbrowser
from pathlib import Path

import customtkinter as ctk
import qrcode
from packaging.version import Version
from PIL import Image

APP = 'SofaPower Hub'
VER = '1.1.0'
REPO = 'Horizongit228/SofaPower'
PREFIX = 'hub-v'

BG = '#08050E'
CARD = '#171020'
CARD2 = '#24172F'
PURPLE = '#8A42FF'
HOVER = '#9D62FF'
TEXT = '#F7F2FF'
MUTED = '#AA9DBA'
OK = '#7FE5A8'
WARN = '#FFD17F'
BAD = '#FF8F9A'
ctk.set_appearance_mode('dark')


def ps(code, timeout=20):
    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0) if os.name == 'nt' else 0
    code = '[Console]::OutputEncoding=[Text.Encoding]::UTF8;' + code
    return subprocess.run(
        ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', code],
        capture_output=True,
        text=True,
        encoding='utf-8',
        errors='replace',
        timeout=timeout,
        creationflags=flags,
    )


def jps(code):
    result = ps(code)
    if result.returncode or not result.stdout.strip():
        return None
    try:
        return json.loads(result.stdout.strip())
    except Exception:
        return None


def normalize_mac(value):
    raw = re.sub(r'[^0-9A-Fa-f]', '', value or '').upper()
    if len(raw) != 12 or raw in {'000000000000', 'FFFFFFFFFFFF'}:
        return None
    return ':'.join(raw[i:i + 2] for i in range(0, 12, 2))


def adapters():
    data = jps(
        "Get-NetAdapter -Physical -ErrorAction SilentlyContinue | "
        "Select Name,InterfaceDescription,MacAddress,Status,ifIndex | ConvertTo-Json -Compress"
    )
    if not data:
        return []
    return data if isinstance(data, list) else [data]


def adapter_details(adapter):
    idx = int(adapter.get('ifIndex', 0) or 0)
    name = str(adapter.get('Name', '')).replace("'", "''")
    desc = str(adapter.get('InterfaceDescription', '')).replace("'", "''")

    network = jps(
        f"$c=Get-NetIPConfiguration -InterfaceIndex {idx} -ErrorAction SilentlyContinue;"
        "$ip=$(if($c.IPv4Address){$c.IPv4Address.IPAddress}else{''});"
        "$gw=$(if($c.IPv4DefaultGateway){$c.IPv4DefaultGateway.NextHop}else{''});"
        "[PSCustomObject]@{IP=$ip;Gateway=$gw}|ConvertTo-Json -Compress"
    ) or {}

    power = jps(
        f"$p=Get-NetAdapterPowerManagement -Name '{name}' -ErrorAction SilentlyContinue;"
        "[PSCustomObject]@{"
        "Magic=$(if($p){[string]$p.WakeOnMagicPacket}else{'Unknown'});"
        "Pattern=$(if($p){[string]$p.WakeOnPattern}else{'Unknown'})"
        "}|ConvertTo-Json -Compress"
    ) or {}

    advanced = jps(
        f"$x=Get-NetAdapterAdvancedProperty -Name '{name}' -AllProperties -ErrorAction SilentlyContinue | "
        "Where-Object {$_.RegistryKeyword -match 'WakeOnMagicPacket|ShutdownWake|S5Wake'} | "
        "Select RegistryKeyword,DisplayName,DisplayValue,RegistryValue;"
        "if($x){$x|ConvertTo-Json -Compress}else{'[]'}"
    )
    if isinstance(advanced, dict):
        advanced = [advanced]
    if not isinstance(advanced, list):
        advanced = []

    armed_result = ps("powercfg /devicequery wake_armed")
    wake_armed_text = armed_result.stdout.lower() if armed_result.returncode == 0 else ''
    wake_armed = bool(desc and desc.lower() in wake_armed_text) or bool(name and name.lower() in wake_armed_text)

    fast_result = ps(
        "$v=(Get-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power' "
        "-Name HiberbootEnabled -ErrorAction SilentlyContinue).HiberbootEnabled;"
        "if($null -eq $v){'?'}else{[string]$v}"
    )
    fast = fast_result.stdout.strip() if fast_result.returncode == 0 else '?'

    return {
        'ip': network.get('IP', '') or '',
        'gateway': network.get('Gateway', '') or '',
        'magic': str(power.get('Magic', 'Unknown')),
        'pattern': str(power.get('Pattern', 'Unknown')),
        'advanced': advanced,
        'wake_armed': wake_armed,
        'fast': fast,
    }


def configure(adapter, disable_fast=True):
    name = str(adapter.get('Name', '')).replace("'", "''")
    desc = str(adapter.get('InterfaceDescription', '')).replace("'", "''")
    fast = '$true' if disable_fast else '$false'

    script = f"""
$n='{name}'
$d='{desc}'
$disableFast={fast}
$ErrorActionPreference='SilentlyContinue'

try {{ Set-NetAdapterPowerManagement -Name $n -WakeOnMagicPacket Enabled -WakeOnPattern Disabled }} catch {{}}
try {{
    $props = Get-NetAdapterAdvancedProperty -Name $n -AllProperties | Where-Object {{
        $_.RegistryKeyword -match 'WakeOnMagicPacket|ShutdownWake|S5Wake'
    }}
    foreach($p in $props) {{
        try {{ Set-NetAdapterAdvancedProperty -Name $n -RegistryKeyword $p.RegistryKeyword -RegistryValue 1 -NoRestart }} catch {{}}
    }}
}} catch {{}}
try {{ powercfg /deviceenablewake \"$d\" | Out-Null }} catch {{}}
if($disableFast) {{
    Set-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power' -Name HiberbootEnabled -Type DWord -Value 0 -Force
}}
try {{ Restart-NetAdapter -Name $n -Confirm:$false }} catch {{}}
Start-Sleep -Milliseconds 300
try {{ Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force }} catch {{}}
"""

    path = Path(tempfile.gettempdir()) / 'SofaPowerHub-config.ps1'
    path.write_text(script, encoding='utf-8-sig')
    result = ctypes.windll.shell32.ShellExecuteW(
        None, 'runas', 'powershell.exe', f'-NoProfile -ExecutionPolicy Bypass -File "{path}"', None, 0
    )
    return result > 32


def latest_release():
    url = f'https://api.github.com/repos/{REPO}/releases?per_page=20'
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': f'{APP}/{VER}',
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
        },
    )
    with urllib.request.urlopen(req, timeout=8) as response:
        data = json.loads(response.read().decode('utf-8'))
    for release in data:
        tag = str(release.get('tag_name', ''))
        if tag.startswith(PREFIX) and not release.get('draft') and not release.get('prerelease'):
            return tag[len(PREFIX):], release.get('html_url')
    return None, None


def asset(name):
    return os.path.join(getattr(sys, '_MEIPASS', os.path.dirname(__file__)), name)


def pairing_uri(adapter, info):
    mac = normalize_mac(adapter.get('MacAddress', ''))
    ip = info.get('ip', '')
    if not mac or not ip:
        return None
    payload = {
        't': 'sofapower',
        'v': 1,
        'name': os.environ.get('COMPUTERNAME') or socket.gethostname() or 'Домашний ПК',
        'mac': mac,
        'ip': ip,
    }
    raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    encoded = base64.urlsafe_b64encode(raw).decode('ascii').rstrip('=')
    return 'sofapower://pair?data=' + encoded


def advanced_magic_enabled(items):
    if not items:
        return None
    seen = False
    for item in items:
        key = str(item.get('RegistryKeyword', '')).lower()
        if 'wake' not in key:
            continue
        seen = True
        values = item.get('RegistryValue')
        display = str(item.get('DisplayValue', '')).lower()
        if values == 1 or values == [1] or 'enabled' in display or 'вкл' in display:
            return True
    return False if seen else None


class Hub(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title(f'{APP} {VER}')
        self.geometry('1040x700')
        self.minsize(900, 620)
        self.configure(fg_color=BG)
        try:
            self.iconbitmap(asset('sofapower_hub.ico'))
        except Exception:
            pass
        self.items = []
        self.current = None
        self.current_info = {}
        self.release = None
        self.fast = ctk.BooleanVar(value=True)
        self.diag_labels = []
        self.build()
        self.after(250, self.refresh)
        self.after(1300, self.check_update)

    def build(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)

        header = ctk.CTkFrame(self, fg_color='transparent')
        header.grid(row=0, column=0, sticky='ew', padx=28, pady=(22, 12))
        header.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(header, text='SofaPower Hub', font=ctk.CTkFont(size=30, weight='bold'), text_color=TEXT).grid(row=0, column=0, sticky='w')
        ctk.CTkLabel(header, text='Настройка, диагностика и QR-связка с SofaPower на Android', font=ctk.CTkFont(size=14), text_color=MUTED).grid(row=1, column=0, sticky='w')
        self.update_button = ctk.CTkButton(header, text=f'v{VER} • Обновления', width=185, height=36, corner_radius=18, fg_color=CARD2, hover_color='#362346', command=self.update_click)
        self.update_button.grid(row=0, column=1, rowspan=2)

        body = ctk.CTkFrame(self, fg_color='transparent')
        body.grid(row=1, column=0, sticky='nsew', padx=28, pady=(0, 18))
        body.grid_columnconfigure(0, weight=3)
        body.grid_columnconfigure(1, weight=2)
        body.grid_rowconfigure(0, weight=1)

        left = ctk.CTkFrame(body, fg_color=CARD, corner_radius=25, border_width=1, border_color='#3A2751')
        left.grid(row=0, column=0, sticky='nsew', padx=(0, 10))
        left.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(left, text='Ethernet-карта', font=ctk.CTkFont(size=20, weight='bold'), text_color=TEXT).grid(row=0, column=0, sticky='w', padx=22, pady=(22, 5))
        ctk.CTkLabel(left, text='Hub ищет только физические сетевые карты. Для Wake-on-LAN обычно нужен активный Ethernet.', text_color=MUTED, font=ctk.CTkFont(size=12), wraplength=560, justify='left').grid(row=1, column=0, sticky='w', padx=22, pady=(0, 14))

        self.menu = ctk.CTkOptionMenu(left, values=['Поиск…'], height=42, corner_radius=14, fg_color=CARD2, button_color=PURPLE, button_hover_color=HOVER, command=self.choose)
        self.menu.grid(row=2, column=0, sticky='ew', padx=22)

        info_box = ctk.CTkFrame(left, fg_color='#100B18', corner_radius=18)
        info_box.grid(row=3, column=0, sticky='ew', padx=22, pady=16)
        info_box.grid_columnconfigure(1, weight=1)
        self.vmac = self.row(info_box, 0, 'MAC', '—')
        self.vstatus = self.row(info_box, 1, 'Состояние', '—')
        self.vip = self.row(info_box, 2, 'IPv4', '—')
        self.vgw = self.row(info_box, 3, 'Шлюз', '—')
        self.vwol = self.row(info_box, 4, 'Magic Packet', '—')
        self.vfast = self.row(info_box, 5, 'Fast Startup', '—')

        quick = ctk.CTkFrame(left, fg_color='transparent')
        quick.grid(row=4, column=0, sticky='ew', padx=22)
        quick.grid_columnconfigure((0, 1, 2), weight=1)
        ctk.CTkButton(quick, text='Обновить', height=40, corner_radius=13, fg_color=CARD2, hover_color='#362346', command=self.refresh).grid(row=0, column=0, sticky='ew', padx=(0, 5))
        ctk.CTkButton(quick, text='Скопировать MAC', height=40, corner_radius=13, fg_color=CARD2, hover_color='#362346', command=self.copy_mac).grid(row=0, column=1, sticky='ew', padx=5)
        self.qr_button = ctk.CTkButton(quick, text='Показать QR', height=40, corner_radius=13, fg_color=CARD2, hover_color='#362346', command=self.show_qr)
        self.qr_button.grid(row=0, column=2, sticky='ew', padx=(5, 0))

        self.go = ctk.CTkButton(left, text='⚡ Настроить Wake-on-LAN', height=58, corner_radius=18, fg_color=PURPLE, hover_color=HOVER, font=ctk.CTkFont(size=17, weight='bold'), command=self.setup)
        self.go.grid(row=5, column=0, sticky='ew', padx=22, pady=(14, 9))
        ctk.CTkCheckBox(left, text='Отключить быстрый запуск Windows', variable=self.fast, fg_color=PURPLE, hover_color=HOVER, text_color=MUTED).grid(row=6, column=0, sticky='w', padx=22, pady=(0, 18))

        privacy = ctk.CTkFrame(left, fg_color='#15101C', corner_radius=14, border_width=1, border_color='#342641')
        privacy.grid(row=7, column=0, sticky='ew', padx=22, pady=(0, 22))
        ctk.CTkLabel(privacy, text='Приватность', font=ctk.CTkFont(size=12, weight='bold'), text_color='#D9C5FF').pack(anchor='w', padx=14, pady=(10, 2))
        ctk.CTkLabel(privacy, text='MAC и локальный IP не отправляются в облако и не сохраняются Hub на диск. QR создаётся только в памяти.', text_color=MUTED, font=ctk.CTkFont(size=11), wraplength=560, justify='left').pack(anchor='w', padx=14, pady=(0, 10))

        right = ctk.CTkFrame(body, fg_color=CARD, corner_radius=25, border_width=1, border_color='#3A2751')
        right.grid(row=0, column=1, sticky='nsew', padx=(10, 0))
        right.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(right, text='Диагностика WoL', font=ctk.CTkFont(size=20, weight='bold'), text_color=TEXT).grid(row=0, column=0, sticky='w', padx=22, pady=(22, 6))
        self.summary = ctk.CTkLabel(right, text='Запускаю проверку…', text_color=MUTED, font=ctk.CTkFont(size=12), justify='left')
        self.summary.grid(row=1, column=0, sticky='w', padx=22, pady=(0, 12))

        diag = ctk.CTkFrame(right, fg_color='#100B18', corner_radius=18)
        diag.grid(row=2, column=0, sticky='ew', padx=22)
        labels = ['Физический Ethernet', 'Линк активен', 'MAC корректный', 'IPv4 получен', 'Wake on Magic Packet', 'Расширенная настройка Magic Packet', 'Windows разрешает пробуждение', 'Fast Startup выключен']
        for i, label in enumerate(labels):
            line = ctk.CTkLabel(diag, text='◌  ' + label, text_color=MUTED, font=ctk.CTkFont(size=12), anchor='w')
            line.grid(row=i, column=0, sticky='ew', padx=14, pady=6)
            self.diag_labels.append(line)

        bios = ctk.CTkFrame(right, fg_color='#251632', corner_radius=16, border_width=1, border_color='#573470')
        bios.grid(row=3, column=0, sticky='ew', padx=22, pady=18)
        ctk.CTkLabel(bios, text='BIOS проверяется вручную', text_color='#DDBEFF', font=ctk.CTkFont(size=14, weight='bold')).pack(anchor='w', padx=15, pady=(13, 5))
        ctk.CTkLabel(bios, text='Wake on LAN / PCI-E — Enabled\nErP / EuP — Disabled', text_color=TEXT, justify='left').pack(anchor='w', padx=15, pady=(0, 13))

        self.msg = ctk.CTkLabel(right, text='Готово к проверке.', text_color=MUTED, font=ctk.CTkFont(size=12), wraplength=330, justify='left')
        self.msg.grid(row=4, column=0, sticky='nw', padx=22, pady=(0, 10))
        ctk.CTkButton(right, text='Открыть сетевые подключения Windows', height=40, corner_radius=13, fg_color=CARD2, hover_color='#362346', command=lambda: subprocess.Popen(['control.exe', 'ncpa.cpl'])).grid(row=5, column=0, sticky='ew', padx=22, pady=(0, 22))

        ctk.CTkLabel(self, text='SofaPower Hub не использует аккаунты, аналитику или рекламные SDK.', text_color='#786F86', font=ctk.CTkFont(size=11)).grid(row=2, column=0, pady=(0, 10))

    def row(self, parent, number, key, value):
        ctk.CTkLabel(parent, text=key, text_color=MUTED, font=ctk.CTkFont(size=12)).grid(row=number, column=0, sticky='w', padx=(14, 10), pady=7)
        out = ctk.CTkLabel(parent, text=value, text_color=TEXT, font=ctk.CTkFont(size=12, weight='bold'))
        out.grid(row=number, column=1, sticky='e', padx=(0, 14), pady=7)
        return out

    def say(self, text, color=MUTED):
        self.msg.configure(text=text, text_color=color)

    def refresh(self):
        self.go.configure(state='disabled', text='Сканирую…')
        self.qr_button.configure(state='disabled')
        threading.Thread(target=self.scan_worker, daemon=True).start()

    def scan_worker(self):
        try:
            found = adapters()
            self.after(0, lambda: self.apply_adapters(found))
        except Exception as error:
            self.after(0, lambda: self.say(f'Ошибка: {error}', WARN))

    def apply_adapters(self, items):
        self.items = items
        if not items:
            self.menu.configure(values=['Не найдено'])
            self.menu.set('Не найдено')
            self.go.configure(state='disabled', text='⚡ Настроить Wake-on-LAN')
            self.qr_button.configure(state='disabled')
            self.say('Физический Ethernet-адаптер не найден.', WARN)
            self.render_diag([False, False, False, False, None, None, None, None])
            return
        values = [f"{a.get('Name')} — {a.get('InterfaceDescription')}" + (' • Up' if str(a.get('Status')).lower() == 'up' else '') for a in items]
        self.menu.configure(values=values)
        index = next((i for i, a in enumerate(items) if str(a.get('Status')).lower() == 'up'), 0)
        self.menu.set(values[index])
        self.current = items[index]
        self.load()

    def choose(self, value):
        try:
            self.current = self.items[list(self.menu.cget('values')).index(value)]
            self.load()
        except Exception:
            pass

    def load(self):
        adapter = self.current
        if not adapter:
            return
        normalized = normalize_mac(adapter.get('MacAddress', '')) or '—'
        self.vmac.configure(text=normalized)
        status = str(adapter.get('Status', '—'))
        self.vstatus.configure(text=status, text_color=OK if status.lower() == 'up' else WARN)
        self.go.configure(state='disabled', text='Проверяю…')
        self.qr_button.configure(state='disabled')
        threading.Thread(target=self.load_worker, args=(adapter.copy(),), daemon=True).start()

    def load_worker(self, adapter):
        try:
            info = adapter_details(adapter)
            self.after(0, lambda: self.apply_details(adapter, info))
        except Exception as error:
            self.after(0, lambda: self.say(f'Ошибка проверки: {error}', WARN))

    def apply_details(self, adapter, info):
        self.current_info = info
        self.vip.configure(text=info.get('ip') or '—')
        self.vgw.configure(text=info.get('gateway') or '—')
        magic = info.get('magic', 'Unknown')
        self.vwol.configure(text=magic, text_color=OK if magic.lower() == 'enabled' else WARN)
        fast = info.get('fast', '?')
        fast_text = 'Включён' if fast == '1' else 'Выключен' if fast == '0' else 'Неизвестно'
        self.vfast.configure(text=fast_text, text_color=WARN if fast == '1' else OK if fast == '0' else MUTED)
        advanced = advanced_magic_enabled(info.get('advanced', []))
        checks = [True, str(adapter.get('Status', '')).lower() == 'up', normalize_mac(adapter.get('MacAddress', '')) is not None, bool(info.get('ip')), magic.lower() == 'enabled', advanced, bool(info.get('wake_armed')), fast == '0']
        self.render_diag(checks)
        self.go.configure(state='normal', text='⚡ Настроить Wake-on-LAN')
        self.qr_button.configure(state='normal' if pairing_uri(adapter, info) else 'disabled')
        solid = sum(1 for x in checks if x is True)
        known = sum(1 for x in checks if x is not None)
        if solid == known and known == len(checks):
            self.summary.configure(text='Все проверяемые настройки Windows готовы ✓', text_color=OK)
            self.say('Windows выглядит готовой к Wake-on-LAN. BIOS остаётся проверить вручную.', OK)
        else:
            self.summary.configure(text=f'Готово {solid} из {known} проверяемых пунктов', text_color=WARN)
            self.say('Нажми «Настроить Wake-on-LAN», чтобы Hub исправил доступные параметры Windows.', WARN)

    def render_diag(self, checks):
        for label, state in zip(self.diag_labels, checks):
            base = label.cget('text')
            if len(base) > 2 and base[1:3] == '  ':
                base = base[3:]
            if state is True:
                label.configure(text='●  ' + base, text_color=OK)
            elif state is False:
                label.configure(text='●  ' + base, text_color=WARN)
            else:
                label.configure(text='◌  ' + base, text_color=MUTED)

    def copy_mac(self):
        if not self.current:
            return
        value = normalize_mac(self.current.get('MacAddress', ''))
        if not value:
            self.say('У адаптера нет корректного MAC.', WARN)
            return
        self.clipboard_clear()
        self.clipboard_append(value)
        self.say('MAC скопирован в буфер обмена.', OK)

    def show_qr(self):
        if not self.current:
            return
        uri = pairing_uri(self.current, self.current_info)
        if not uri:
            self.say('Для QR нужны корректные MAC и IPv4.', WARN)
            return
        qr = qrcode.QRCode(version=None, box_size=10, border=3)
        qr.add_data(uri)
        qr.make(fit=True)
        image = qr.make_image(fill_color='black', back_color='white').convert('RGB')
        image = image.resize((300, 300), Image.Resampling.NEAREST)

        top = ctk.CTkToplevel(self)
        top.title('Связать SofaPower с телефоном')
        top.geometry('430x540')
        top.resizable(False, False)
        top.configure(fg_color=BG)
        top.transient(self)
        top.grab_set()
        ctk.CTkLabel(top, text='Сканируй в SofaPower', font=ctk.CTkFont(size=22, weight='bold'), text_color=TEXT).pack(pady=(22, 5))
        ctk.CTkLabel(top, text='Android → «Связать с Hub»', text_color=MUTED).pack()
        qr_image = ctk.CTkImage(light_image=image, dark_image=image, size=(300, 300))
        top._qr_image = qr_image
        ctk.CTkLabel(top, image=qr_image, text='').pack(pady=18)
        pc_name = os.environ.get('COMPUTERNAME') or socket.gethostname() or 'Домашний ПК'
        ctk.CTkLabel(top, text=f'{pc_name} • {self.current_info.get("ip", "")}\nQR содержит только локальные MAC + IP и не загружается в интернет.', text_color=MUTED, font=ctk.CTkFont(size=11), justify='center', wraplength=380).pack(padx=20)

        def copy_pair():
            self.clipboard_clear()
            self.clipboard_append(uri)
            self.say('Код связки скопирован.', OK)

        ctk.CTkButton(top, text='Скопировать код связки', fg_color=CARD2, hover_color='#362346', corner_radius=14, command=copy_pair).pack(pady=14)

    def setup(self):
        if not self.current:
            return
        try:
            if configure(self.current, bool(self.fast.get())):
                self.say('Подтверди UAC. Hub применяет настройки и перезапустит Ethernet на несколько секунд…', OK)
                self.after(3500, self.refresh)
            else:
                self.say('Запрос прав администратора отменён.', WARN)
        except Exception as error:
            self.say(f'Не удалось запустить настройку: {error}', WARN)

    def check_update(self):
        threading.Thread(target=self.update_worker, daemon=True).start()

    def update_worker(self):
        try:
            version, url = latest_release()
            newer = bool(version and Version(version) > Version(VER))
            self.after(0, lambda: self.update_state(version, url, newer))
        except Exception:
            self.after(0, lambda: self.update_button.configure(text=f'v{VER} • Обновления'))

    def update_state(self, version, url, newer):
        if newer:
            self.release = url
            self.update_button.configure(text=f'Доступно v{version}', fg_color=PURPLE, hover_color=HOVER)
            self.say(f'Есть новая версия Hub v{version}. Нажми кнопку обновления.', OK)
        else:
            self.release = None
            self.update_button.configure(text=f'v{VER} • Актуально', fg_color=CARD2, hover_color='#362346')

    def update_click(self):
        if self.release:
            webbrowser.open(self.release)
        else:
            self.update_button.configure(text='Проверяю…')
            self.check_update()


if __name__ == '__main__':
    Hub().mainloop()
