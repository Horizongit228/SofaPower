import ctypes, json, os, re, subprocess, sys, tempfile, threading, urllib.request, webbrowser
from pathlib import Path
import customtkinter as ctk
from packaging.version import Version

APP='SofaPower Hub'; VER='1.0.0'; REPO='Horizongit228/SofaPower'; PREFIX='hub-v'
BG='#09060F'; CARD='#171020'; CARD2='#24172F'; PURPLE='#8A42FF'; HOVER='#9D62FF'; TEXT='#F7F2FF'; MUTED='#AA9DBA'; OK='#7FE5A8'; WARN='#FFD17F'
ctk.set_appearance_mode('dark')


def ps(code, timeout=15):
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0) if os.name=='nt' else 0
    code='[Console]::OutputEncoding=[Text.Encoding]::UTF8;'+code
    return subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-Command',code],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout,creationflags=flags)


def jps(code):
    r=ps(code)
    if r.returncode or not r.stdout.strip(): return None
    try: return json.loads(r.stdout.strip())
    except Exception: return None


def mac(v):
    s=re.sub(r'[^0-9A-Fa-f]','',v or '').upper()
    return ':'.join(s[i:i+2] for i in range(0,12,2)) if len(s)==12 else (v or '—')


def adapters():
    d=jps("Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Select Name,InterfaceDescription,MacAddress,Status,ifIndex | ConvertTo-Json -Compress")
    if not d: return []
    return d if isinstance(d,list) else [d]


def details(a):
    idx=int(a.get('ifIndex',0) or 0); name=str(a.get('Name','')).replace("'","''")
    ip=jps(f"$c=Get-NetIPConfiguration -InterfaceIndex {idx} -ErrorAction SilentlyContinue; if($c.IPv4Address){{[PSCustomObject]@{{IP=$c.IPv4Address.IPAddress;Gateway=$(if($c.IPv4DefaultGateway){{$c.IPv4DefaultGateway.NextHop}}else{{''}})}}|ConvertTo-Json -Compress}}") or {}
    wol=jps(f"$p=Get-NetAdapterPowerManagement -Name '{name}' -ErrorAction SilentlyContinue; [PSCustomObject]@{{Magic=$(if($p){{[string]$p.WakeOnMagicPacket}}else{{'Unknown'}})}}|ConvertTo-Json -Compress") or {}
    r=ps("$v=(Get-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power' -Name HiberbootEnabled -ErrorAction SilentlyContinue).HiberbootEnabled; if($null -eq $v){'?' }else{[string]$v}")
    fast=r.stdout.strip() if not r.returncode else '?'
    return ip,wol.get('Magic','Unknown'),fast


def configure(a, disable_fast=True):
    name=str(a.get('Name','')).replace("'","''"); desc=str(a.get('InterfaceDescription','')).replace("'","''")
    fast='$true' if disable_fast else '$false'
    script=f"""$n='{name}';$d='{desc}';$fast={fast};
try{{Set-NetAdapterPowerManagement -Name $n -WakeOnMagicPacket Enabled -ErrorAction SilentlyContinue}}catch{{}}
try{{$x=Get-NetAdapterAdvancedProperty -Name $n -AllProperties -ErrorAction SilentlyContinue|?{{$_.RegistryKeyword -match 'WakeOnMagicPacket'}}|select -First 1;if($x){{Set-NetAdapterAdvancedProperty -Name $n -RegistryKeyword $x.RegistryKeyword -RegistryValue 1 -NoRestart -ErrorAction SilentlyContinue}}}}catch{{}}
powercfg /deviceenablewake \"$d\" | Out-Null
if($fast){{Set-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power' -Name HiberbootEnabled -Type DWord -Value 0 -Force}}
Restart-NetAdapter -Name $n -Confirm:$false -ErrorAction SilentlyContinue
'SofaPower Hub: настройка завершена'|Set-Content $env:TEMP\\SofaPowerHub-result.txt -Encoding UTF8"""
    p=Path(tempfile.gettempdir())/'SofaPowerHub.ps1'; p.write_text(script,encoding='utf-8-sig')
    rc=ctypes.windll.shell32.ShellExecuteW(None,'runas','powershell.exe',f'-NoProfile -ExecutionPolicy Bypass -File "{p}"',None,0)
    return rc>32


def latest_release():
    u=f'https://api.github.com/repos/{REPO}/releases?per_page=20'
    req=urllib.request.Request(u,headers={'User-Agent':f'{APP}/{VER}','Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(req,timeout=8) as r: data=json.loads(r.read().decode())
    for rel in data:
        t=str(rel.get('tag_name',''))
        if t.startswith(PREFIX) and not rel.get('draft'): return t[len(PREFIX):],rel.get('html_url')
    return None,None


def asset(n):
    return os.path.join(getattr(sys,'_MEIPASS',os.path.dirname(__file__)),n)


class Hub(ctk.CTk):
    def __init__(self):
        super().__init__(); self.title(f'{APP} {VER}'); self.geometry('900x620'); self.minsize(780,560); self.configure(fg_color=BG)
        try:self.iconbitmap(asset('sofapower_hub.ico'))
        except Exception:pass
        self.items=[]; self.cur=None; self.release=None; self.fast=ctk.BooleanVar(value=True)
        self.build(); self.after(250,self.refresh); self.after(1200,self.check_update)

    def build(self):
        self.grid_columnconfigure(0,weight=1); self.grid_rowconfigure(1,weight=1)
        h=ctk.CTkFrame(self,fg_color='transparent'); h.grid(row=0,column=0,sticky='ew',padx=28,pady=(22,12)); h.grid_columnconfigure(0,weight=1)
        ctk.CTkLabel(h,text='SofaPower Hub',font=ctk.CTkFont(size=30,weight='bold'),text_color=TEXT).grid(row=0,column=0,sticky='w')
        ctk.CTkLabel(h,text='Быстрая настройка Wake-on-LAN для Windows',font=ctk.CTkFont(size=14),text_color=MUTED).grid(row=1,column=0,sticky='w')
        self.up=ctk.CTkButton(h,text=f'v{VER} • Обновления',width=180,height=34,corner_radius=17,fg_color=CARD2,hover_color='#362346',command=self.update_click); self.up.grid(row=0,column=1,rowspan=2)

        b=ctk.CTkFrame(self,fg_color='transparent'); b.grid(row=1,column=0,sticky='nsew',padx=28,pady=(0,26)); b.grid_columnconfigure(0,weight=3); b.grid_columnconfigure(1,weight=2); b.grid_rowconfigure(0,weight=1)
        l=ctk.CTkFrame(b,fg_color=CARD,corner_radius=25,border_width=1,border_color='#3A2751'); l.grid(row=0,column=0,sticky='nsew',padx=(0,10)); l.grid_columnconfigure(0,weight=1)
        ctk.CTkLabel(l,text='Ethernet-карта',font=ctk.CTkFont(size=20,weight='bold'),text_color=TEXT).grid(row=0,column=0,sticky='w',padx=22,pady=(22,5))
        ctk.CTkLabel(l,text='Hub сам найдёт физические адаптеры. Обычно нужен тот, где статус Up.',text_color=MUTED,font=ctk.CTkFont(size=12),wraplength=490,justify='left').grid(row=1,column=0,sticky='w',padx=22,pady=(0,14))
        self.menu=ctk.CTkOptionMenu(l,values=['Поиск…'],height=42,corner_radius=14,fg_color=CARD2,button_color=PURPLE,button_hover_color=HOVER,command=self.choose); self.menu.grid(row=2,column=0,sticky='ew',padx=22)
        box=ctk.CTkFrame(l,fg_color='#100B18',corner_radius=18); box.grid(row=3,column=0,sticky='ew',padx=22,pady=16); box.grid_columnconfigure(1,weight=1)
        self.vmac=self.row(box,0,'MAC','—'); self.vstatus=self.row(box,1,'Состояние','—'); self.vip=self.row(box,2,'IPv4','—'); self.vgw=self.row(box,3,'Шлюз','—'); self.vwol=self.row(box,4,'Magic Packet','—'); self.vfast=self.row(box,5,'Fast Startup','—')
        rr=ctk.CTkFrame(l,fg_color='transparent'); rr.grid(row=4,column=0,sticky='ew',padx=22); rr.grid_columnconfigure((0,1),weight=1)
        ctk.CTkButton(rr,text='Обновить',height=40,corner_radius=13,fg_color=CARD2,hover_color='#362346',command=self.refresh).grid(row=0,column=0,sticky='ew',padx=(0,5))
        ctk.CTkButton(rr,text='Скопировать MAC',height=40,corner_radius=13,fg_color=CARD2,hover_color='#362346',command=self.copy_mac).grid(row=0,column=1,sticky='ew',padx=(5,0))
        self.go=ctk.CTkButton(l,text='⚡ Настроить Wake-on-LAN',height=58,corner_radius=18,fg_color=PURPLE,hover_color=HOVER,font=ctk.CTkFont(size=17,weight='bold'),command=self.setup); self.go.grid(row=5,column=0,sticky='ew',padx=22,pady=(14,9))
        ctk.CTkCheckBox(l,text='Отключить быстрый запуск Windows',variable=self.fast,fg_color=PURPLE,hover_color=HOVER,text_color=MUTED).grid(row=6,column=0,sticky='w',padx=22,pady=(0,20))

        r=ctk.CTkFrame(b,fg_color=CARD,corner_radius=25,border_width=1,border_color='#3A2751'); r.grid(row=0,column=1,sticky='nsew',padx=(10,0)); r.grid_columnconfigure(0,weight=1); r.grid_rowconfigure(4,weight=1)
        ctk.CTkLabel(r,text='Что настроит Hub',font=ctk.CTkFont(size=20,weight='bold'),text_color=TEXT).grid(row=0,column=0,sticky='w',padx=22,pady=(22,8))
        ctk.CTkLabel(r,text='• Wake on Magic Packet\n\n• разрешение сетевой карте будить ПК\n\n• Fast Startup — по желанию\n\n• перезапуск адаптера после настройки',text_color='#DED3EC',font=ctk.CTkFont(size=13),justify='left').grid(row=1,column=0,sticky='w',padx=22)
        bios=ctk.CTkFrame(r,fg_color='#251632',corner_radius=16,border_width=1,border_color='#573470'); bios.grid(row=2,column=0,sticky='ew',padx=22,pady=20)
        ctk.CTkLabel(bios,text='BIOS остаётся вручную',text_color='#DDBEFF',font=ctk.CTkFont(size=14,weight='bold')).pack(anchor='w',padx=15,pady=(13,5)); ctk.CTkLabel(bios,text='Wake on LAN / PCI-E — Enabled\nErP / EuP — Disabled',text_color=TEXT,justify='left').pack(anchor='w',padx=15,pady=(0,13))
        self.msg=ctk.CTkLabel(r,text='Готово к проверке.',text_color=MUTED,font=ctk.CTkFont(size=12),wraplength=290,justify='left'); self.msg.grid(row=3,column=0,sticky='nw',padx=22,pady=(0,12))
        ctk.CTkButton(r,text='Открыть сетевые подключения Windows',height=40,corner_radius=13,fg_color=CARD2,hover_color='#362346',command=lambda:subprocess.Popen(['control.exe','ncpa.cpl'])).grid(row=5,column=0,sticky='ew',padx=22,pady=(0,22))

    def row(self,p,n,k,v):
        ctk.CTkLabel(p,text=k,text_color=MUTED,font=ctk.CTkFont(size=12)).grid(row=n,column=0,sticky='w',padx=(14,10),pady=7)
        x=ctk.CTkLabel(p,text=v,text_color=TEXT,font=ctk.CTkFont(size=12,weight='bold')); x.grid(row=n,column=1,sticky='e',padx=(0,14),pady=7); return x

    def say(self,t,color=MUTED): self.msg.configure(text=t,text_color=color)

    def refresh(self): self.go.configure(state='disabled',text='Сканирую…'); threading.Thread(target=self.scan,daemon=True).start()
    def scan(self):
        try:self.after(0,lambda:self.apply_adapters(adapters()))
        except Exception as e:self.after(0,lambda:self.say(f'Ошибка: {e}',WARN))
    def apply_adapters(self,items):
        self.items=items
        if not items:self.menu.configure(values=['Не найдено']);self.menu.set('Не найдено');self.go.configure(state='disabled',text='⚡ Настроить Wake-on-LAN');self.say('Физический Ethernet-адаптер не найден.',WARN);return
        vals=[f"{a.get('Name')} — {a.get('InterfaceDescription')}"+(' • Up' if str(a.get('Status')).lower()=='up' else '') for a in items]; self.menu.configure(values=vals)
        i=next((i for i,a in enumerate(items) if str(a.get('Status')).lower()=='up'),0); self.menu.set(vals[i]); self.cur=items[i]; self.load()
    def choose(self,v):
        try:self.cur=self.items[list(self.menu.cget('values')).index(v)];self.load()
        except Exception:pass
    def load(self):
        a=self.cur; self.vmac.configure(text=mac(a.get('MacAddress',''))); st=str(a.get('Status','—')); self.vstatus.configure(text=st,text_color=OK if st.lower()=='up' else WARN); self.go.configure(state='disabled',text='Проверяю…'); threading.Thread(target=self.load_worker,args=(a.copy(),),daemon=True).start()
    def load_worker(self,a):
        try:self.after(0,lambda:self.apply_details(*details(a)))
        except Exception as e:self.after(0,lambda:self.say(f'Ошибка проверки: {e}',WARN))
    def apply_details(self,ip,wol,fast):
        self.vip.configure(text=ip.get('IP','—')); self.vgw.configure(text=ip.get('Gateway','—') or '—'); self.vwol.configure(text=wol,text_color=OK if str(wol).lower()=='enabled' else WARN); ft='Включён' if fast=='1' else 'Выключен' if fast=='0' else 'Неизвестно'; self.vfast.configure(text=ft,text_color=WARN if fast=='1' else OK if fast=='0' else MUTED); self.go.configure(state='normal',text='⚡ Настроить Wake-on-LAN'); self.say('Проверка завершена.')
    def copy_mac(self):
        if not self.cur:return
        m=mac(self.cur.get('MacAddress',''));self.clipboard_clear();self.clipboard_append(m);self.say(f'MAC скопирован: {m}',OK)
    def setup(self):
        if not self.cur:return
        try:
            if configure(self.cur,bool(self.fast.get())):self.say('Подтверди UAC. Hub применяет настройки…',OK);self.after(2800,self.refresh)
            else:self.say('Запрос прав администратора отменён.',WARN)
        except Exception as e:self.say(f'Не удалось запустить настройку: {e}',WARN)
    def check_update(self):threading.Thread(target=self.update_worker,daemon=True).start()
    def update_worker(self):
        try:
            v,u=latest_release(); newer=bool(v and Version(v)>Version(VER)); self.after(0,lambda:self.update_state(v,u,newer))
        except Exception:self.after(0,lambda:self.up.configure(text=f'v{VER} • Обновления'))
    def update_state(self,v,u,newer):
        if newer:self.release=u;self.up.configure(text=f'Доступно v{v}',fg_color=PURPLE,hover_color=HOVER);self.say(f'Есть новая версия v{v}. Нажми на кнопку обновлений.',OK)
        else:self.release=None;self.up.configure(text=f'v{VER} • Актуально',fg_color=CARD2,hover_color='#362346')
    def update_click(self):
        if self.release:webbrowser.open(self.release)
        else:self.up.configure(text='Проверяю…');self.check_update()


if __name__=='__main__': Hub().mainloop()
