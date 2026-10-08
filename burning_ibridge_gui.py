#!/usr/bin/env python3
"""Burning-iBridge desktop GUI for Linux and macOS.

Requires Python 3.10+ and Tkinter. Uses installed palera1n, ipsw, iproxy,
libimobiledevice and OpenSSH CLIs. No unauthorized policy writes are implemented.
"""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import hashlib
import os
import platform
import shlex
import shutil
import socket
import subprocess
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk
import webbrowser

HOME = Path.home() / "Burning-iBridge"
HOME.mkdir(parents=True, exist_ok=True)
TOOLS = ("palera1n", "ipsw", "iproxy", "irecovery", "idevice_id",
         "idevicerestore", "usbmuxd", "ssh", "scp", "lsusb")
PRESETS = {
    "Identity": "id; uname -a",
    "EFI manager": "ioreg -p IOService -r -c MacEFIManager -l -w 0",
    "EFI clients": "ioreg -p IOService -r -c MacEFIManagerUserClient -l -w 0",
    "SEP manager": "ioreg -p IOService -r -c AppleSEPManager -l -w 0 | head -80",
    "Credential manager": "ioreg -p IOService -r -c AppleCredentialManager -l -w 0 | head -80",
    "Filtered NVRAM": "nvram -p | grep -Ei 'secure|boot|policy|external' || true",
    "EFI processes": "ps -A -o pid,comm | grep -Ei 'multiversed|powerchimed' || true",
    "dyld inventory": "ls -ln /System/Library/Caches/com.apple.dyld/dyld_shared_cache_arm64*",
}


def timestamped(name):
    d = HOME / "logs" / (name + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def binary(name):
    path = shutil.which(name)
    if name == "palera1n" and not path:
        for p in (Path.home() / "Downloads/palera1n-linux-x86_64/bin/palera1n",
                  Path.home() / "Downloads/palera1n-macos-universal/bin/palera1n",
                  Path.home() / "Downloads/palera1n"):
            if p.is_file() and os.access(p, os.X_OK):
                return str(p)
    return path


def listening(port):
    with socket.socket() as s:
        s.settimeout(.5)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


def terminal(script):
    if platform.system() == "Darwin":
        subprocess.Popen(["open", "-a", "Terminal", str(script)])
    else:
        for prog, argv in (
            ("konsole", ["konsole", "-e", "bash", str(script)]),
            ("gnome-terminal", ["gnome-terminal", "--", "bash", str(script)]),
            ("xfce4-terminal", ["xfce4-terminal", "--command", "bash " + shlex.quote(str(script))]),
            ("xterm", ["xterm", "-e", "bash", str(script)])
        ):
            if shutil.which(prog):
                subprocess.Popen(argv)
                return
        raise RuntimeError("No terminal installed. Run: bash " + shlex.quote(str(script)))


class BurningIBridge(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Burning-iBridge | Linux / macOS")
        self.geometry("1020x770")
        self.minsize(800, 560)
        self.proxy = None
        self.steps = []
        self.port = tk.StringVar(value="2233")
        self.palera = tk.StringVar(value=binary("palera1n") or "")
        self.cache = tk.StringVar()
        self.iso = tk.StringVar()
        self.distro = tk.StringVar(value="Ubuntu")
        self.preset = tk.StringVar(value="Identity")
        self.rootful = tk.BooleanVar(value=True)
        self.debug = tk.BooleanVar(value=True)
        self.draw()
        self.protocol("WM_DELETE_WINDOW", self.close)

    def write(self, message):
        self.console.insert("end", str(message) + "\n")
        self.console.see("end")

    def row(self, parent):
        row = ttk.Frame(parent)
        row.pack(fill="x", pady=5)
        return row

    def button(self, parent, label, callback):
        ttk.Button(parent, text=label, command=callback).pack(side="left", padx=4)

    def filefield(self, parent, label, var):
        r = self.row(parent)
        ttk.Label(r, text=label, width=22).pack(side="left")
        ttk.Entry(r, textvariable=var).pack(side="left", fill="x", expand=True)
        def choose():
            path = filedialog.askopenfilename()
            if path:
                var.set(path)
        self.button(r, "Browse…", choose)

    def draw(self):
        ttk.Label(self, text="Burning-iBridge", font=("TkDefaultFont", 18, "bold")).pack(anchor="w", padx=12, pady=7)
        ttk.Label(self, text="CLI research workstation: DFU / checkm8 / SSH / dyld APIs / scripts / logs").pack(anchor="w", padx=12)
        tabs = ttk.Notebook(self)
        tabs.pack(fill="both", expand=True, padx=12, pady=8)
        views = [ttk.Frame(tabs, padding=12) for _ in range(5)]
        for p, name in zip(views, ("Tools", "Jailbreak & SSH", "Script builder", "ipsw dyld", "OS planning")):
            tabs.add(p, text=name)
        self.draw_tools(views[0])
        self.draw_bridge(views[1])
        self.draw_scripts(views[2])
        self.draw_ipsw(views[3])
        self.draw_os(views[4])
        self.console = scrolledtext.ScrolledText(self, height=10, font=("TkFixedFont", 10))
        self.console.pack(fill="both", padx=12, pady=(0, 12))
        self.write("Logs and generated terminal scripts: ~/Burning-iBridge/logs/")
        self.detect()

    def draw_tools(self, p):
        r = self.row(p)
        self.button(r, "Detect installed CLIs", self.detect)
        self.button(r, "Generate host setup script", self.setup)
        self.button(r, "Detect USB mode", self.usb)
        self.tools_display = scrolledtext.ScrolledText(p, height=11, font=("TkFixedFont", 10))
        self.tools_display.pack(fill="both", expand=True)
        r = self.row(p)
        self.button(r, "irecovery -q", lambda: self.host(["irecovery", "-q"], "irecovery"))
        self.button(r, "idevice_id -l", lambda: self.host(["idevice_id", "-l"], "idevice_id"))
        self.button(r, "idevicerestore -h", lambda: self.host(["idevicerestore", "-h"], "restore-help"))
        r = self.row(p)
        self.button(r, "palera1n releases", lambda: webbrowser.open("https://github.com/palera1n/palera1n/releases"))
        self.button(r, "ipsw releases", lambda: webbrowser.open("https://github.com/blacktop/ipsw/releases/latest"))

    def draw_bridge(self, p):
        self.filefield(p, "palera1n executable", self.palera)
        r = self.row(p)
        ttk.Checkbutton(r, text="rootful (-f)", variable=self.rootful).pack(side="left")
        ttk.Checkbutton(r, text="debug (-d)", variable=self.debug).pack(side="left")
        self.button(r, "Run palera1n --cli", self.jailbreak)
        r = self.row(p)
        ttk.Label(r, text="Local SSH port").pack(side="left")
        ttk.Entry(r, textvariable=self.port, width=8).pack(side="left", padx=5)
        self.button(r, "Start iproxy → T2:44", self.start_proxy)
        self.button(r, "Stop managed iproxy", self.stop_proxy)
        r = self.row(p)
        self.button(r, "SSH test", lambda: self.remote("id; uname -a", "ssh-test"))
        self.button(r, "EFI manager", lambda: self.remote(PRESETS["EFI manager"], "efi-manager"))
        self.button(r, "SEP status", lambda: self.remote(PRESETS["SEP manager"], "sep"))
        ttk.Label(p, text="SSH password prompts appear in a terminal. Confirm SSH host fingerprint yourself.\n"
                  "A jailbroken T2 is not proof of access to Secure Enclave boot-policy writes.", wraplength=820).pack(anchor="w", pady=14)

    def draw_scripts(self, p):
        r = self.row(p)
        ttk.Combobox(r, values=list(PRESETS), textvariable=self.preset, state="readonly", width=32).pack(side="left")
        self.button(r, "Add preset", self.add_preset)
        self.button(r, "Add custom", self.add_custom)
        self.button(r, "Remove selected", self.remove)
        self.entries = tk.Listbox(p, height=7)
        self.entries.pack(fill="x", pady=6)
        ttk.Label(p, text="Custom bridgeOS command (runs as root):").pack(anchor="w")
        self.custom = scrolledtext.ScrolledText(p, height=5)
        self.custom.pack(fill="x")
        r = self.row(p)
        self.button(r, "Preview", self.preview)
        self.button(r, "Save .sh…", self.save)
        self.button(r, "Run queued script", self.run_queue)

    def draw_ipsw(self, p):
        self.filefield(p, "Local dyld main cache", self.cache)
        r = self.row(p)
        self.button(r, "Fetch cache via SCP", self.fetch_cache)
        self.button(r, "Analyze EFI functions", self.ipsw)
        self.button(r, "ipsw docs", lambda: webbrowser.open("https://blacktop.github.io/ipsw/docs/guides/dyld/"))
        ttk.Label(p, text="Fetch retrieves the main dyld cache and subcaches to a timestamped folder.\n"
                  "Analysis checks libMacEFIHostInterface and setNVRAMVariable, without modifying the T2.",
                  wraplength=820).pack(anchor="w", pady=15)

    def draw_os(self, p):
        ttk.Combobox(self.row(p), values=["Ubuntu", "Debian", "Fedora", "Other Linux"],
                     textvariable=self.distro, state="readonly").pack(side="left")
        self.filefield(p, "Installer ISO", self.iso)
        r = self.row(p)
        self.button(r, "SHA-256 ISO", self.checksum)
        self.button(r, "Generate installation checklist", self.os_plan)
        self.button(r, "T2Linux wiki", lambda: webbrowser.open("https://wiki.t2linux.org/"))
        ttk.Label(p, text="OS installation is not implemented yet. Before any disk-writing step, verify "
                  "T2 compatibility, authorized external boot, Secure Boot configuration and backups.",
                  wraplength=820).pack(anchor="w", pady=15)

    def detect(self):
        results = "\n".join(f"{t:16} {binary(t) or 'MISSING'}" for t in TOOLS)
        self.tools_display.delete("1.0", "end")
        self.tools_display.insert("1.0", results)

    def run_terminal(self, body, name):
        folder = timestamped(name)
        script = folder / "run.sh"
        logfile = folder / "terminal.log"
        script.write_text("#!/usr/bin/env bash\nset -o pipefail\n"
                          + body + " 2>&1 | tee " + shlex.quote(str(logfile)) + "\n"
                          + "result=" + "$" + "{PIPESTATUS[0]}\n"
                          + "echo \"exit status: $result\"\n"
                          + "read -r -p 'Press Enter to close...' _ || true\n"
                          + "exit \"$result\"\n")
        script.chmod(0o700)
        self.write(f"{name}: {script} (log: {logfile})")
        terminal(script)

    def host(self, argv, title):
        if not binary(argv[0]):
            messagebox.showerror("Missing tool", argv[0] + " is not installed.")
            return
        self.run_terminal(shlex.join([binary(argv[0]), *argv[1:]]), title)

    def usb(self):
        if platform.system() == "Linux":
            self.host(["lsusb", "-d", "05ac:"], "usb")
        else:
            self.host(["system_profiler", "SPUSBDataType"], "usb")

    def setup(self):
        if platform.system() == "Linux":
            cmd = "sudo apt-get update && sudo apt-get install -y python3-tk usbmuxd libusbmuxd-tools libimobiledevice-utils"
            comment = "# For ipsw: sudo snap install ipsw"
        elif platform.system() == "Darwin":
            cmd = "brew install libusbmuxd libimobiledevice libirecovery blacktop/tap/ipsw"
            comment = ""
        else:
            messagebox.showerror("Platform", "Linux/macOS only")
            return
        if messagebox.askyesno("Host setup", "Generate and launch a REVIEWABLE system package script?"):
            self.run_terminal(comment + "\n" + cmd, "host-install")

    def jailbreak(self):
        path = Path(self.palera.get()).expanduser()
        if not path.is_file() or not os.access(path, os.X_OK):
            messagebox.showerror("palera1n", "Choose a valid executable")
            return
        args = ["sudo", str(path.resolve()), "--cli"]
        if self.rootful.get():
            args.append("-f")
        if self.debug.get():
            args.append("-d")
        if messagebox.askyesno("Jailbreak", "Run the selected palera1n CLI in a terminal?\n" + shlex.join(args)):
            self.run_terminal(shlex.join(args), "palera1n")

    def start_proxy(self):
        try:
            port = int(self.port.get())
            if not 1024 <= port < 65536:
                raise ValueError("Invalid TCP port")
            if self.proxy and self.proxy.poll() is None:
                self.write("iproxy is already managed by this app")
                return
            if listening(port):
                self.write(f"Port {port} is in use. Verify the existing listener.")
                return
            if not binary("iproxy"):
                raise FileNotFoundError("Install iproxy first.")
            folder = timestamped("iproxy")
            with (folder / "iproxy.log").open("ab", buffering=0) as log:
                self.proxy = subprocess.Popen([binary("iproxy"), str(port), "44"],
                                              stdout=log, stderr=subprocess.STDOUT)
            self.write(f"Managed iproxy PID {self.proxy.pid}, 127.0.0.1:{port} -> bridgeOS:44")
        except Exception as exc:
            messagebox.showerror("iproxy", str(exc))

    def stop_proxy(self):
        if self.proxy and self.proxy.poll() is None:
            self.proxy.terminate()
            self.write("Stopped the iproxy process started by Burning-iBridge")
        self.proxy = None

    def remote(self, cmd, label):
        try:
            port = int(self.port.get())
            argv = ["ssh", "-o", "ControlMaster=no", "-o", "ControlPath=none",
                    "-tt", "-p", str(port), "root@127.0.0.1", cmd]
            self.run_terminal(shlex.join(argv), label)
        except Exception as exc:
            messagebox.showerror("SSH", str(exc))

    def add_preset(self):
        self.steps.append(PRESETS[self.preset.get()])
        self.entries.insert("end", self.preset.get())

    def add_custom(self):
        cmd = self.custom.get("1.0", "end").strip()
        if cmd:
            self.steps.append(cmd)
            self.entries.insert("end", "Custom: " + cmd[:70])

    def remove(self):
        for i in sorted(self.entries.curselection(), reverse=True):
            self.entries.delete(i)
            del self.steps[i]

    def script_content(self):
        if not self.steps:
            raise ValueError("Queue a command first")
        marker = "__BRIDGEOS_SCRIPT_END__"
        while any(marker in s.splitlines() for s in self.steps):
            marker += "_X"
        return ("#!/usr/bin/env bash\nset -euo pipefail\n"
                + f"ssh -p {int(self.port.get())} root@127.0.0.1 'sh -s' <<'{marker}'\n"
                + "\n".join(self.steps) + "\n" + marker + "\n")

    def preview(self):
        try:
            content = self.script_content()
        except Exception as exc:
            messagebox.showerror("Script", str(exc))
            return
        win = tk.Toplevel(self)
        win.title("Script preview")
        box = scrolledtext.ScrolledText(win, height=30, width=105)
        box.pack(fill="both", expand=True)
        box.insert("1.0", content)

    def save(self):
        try:
            content = self.script_content()
        except Exception as exc:
            messagebox.showerror("Script", str(exc))
            return
        path = filedialog.asksaveasfilename(defaultextension=".sh")
        if path:
            Path(path).write_text(content)
            self.write("Saved: " + path)

    def run_queue(self):
        try:
            content = self.script_content()
        except Exception as exc:
            messagebox.showerror("Script", str(exc))
            return
        if messagebox.askyesno("Run script?", "Run queued commands as root on bridgeOS? Review the preview first."):
            folder = timestamped("script")
            script = folder / "remote-script.sh"
            script.write_text(content)
            script.chmod(0o700)
            self.run_terminal("bash " + shlex.quote(str(script)), "script-execution")

    def fetch_cache(self):
        if not messagebox.askyesno("Fetch cache?", "Retrieve dyld cache and companion files from bridgeOS? This is several hundred MB."):
            return
        folder = timestamped("dyld-copy")
        cache = folder / "dyld_shared_cache_arm64"
        self.cache.set(str(cache))
        remote = "/System/Library/Caches/com.apple.dyld/dyld_shared_cache_arm64*"
        self.run_terminal("scp -P " + str(int(self.port.get()))
                          + " -o ControlMaster=no -o ControlPath=none "
                          + shlex.quote("root@127.0.0.1:" + remote) + " " + shlex.quote(str(folder)),
                          "dyld-scp")

    def ipsw(self):
        cache = Path(self.cache.get()).expanduser()
        if not cache.is_file() or not binary("ipsw"):
            messagebox.showerror("ipsw", "Install ipsw and select a local dyld_shared_cache_arm64")
            return
        ipsw = shlex.quote(binary("ipsw"))
        c = shlex.quote(str(cache))
        cmds = [
            ipsw + " dyld info " + c,
            ipsw + " dyld image " + c + " libMacEFIHostInterface -V",
            ipsw + " dyld macho " + c + " libMacEFIHostInterface --symbols",
            ipsw + " dyld macho " + c + " libMacEFIHostInterface --loads",
            ipsw + " dyld symaddr --image libMacEFIHostInterface " + c + " setNVRAMVariable",
            ipsw + " dyld symaddr " + c + " setNVRAMVariable --all",
            ipsw + " dyld symaddr " + c + " getNVRAMVariable --all",
        ]
        self.run_dyld_series(cmds)

    def run_dyld_series(self, commands):
        """Execute ipsw probes sequentially, writing one log per probe."""
        folder = timestamped("ipsw-dyld")
        summary = folder / "00_summary.txt"
        script = folder / "run-analysis.sh"
        lines = ["#!/usr/bin/env bash", "set -u", "echo 'Running ipsw dyld research probes'"]
        for i, cmd in enumerate(commands, 1):
            label = "probe-" + str(i).zfill(2)
            logfile = folder / (label + ".log")
            lines.append("echo " + shlex.quote(label + ": " + cmd))
            lines.append(cmd + " > " + shlex.quote(str(logfile)) + " 2>&1")
            lines.append("status=$?")
            lines.append("printf '%s exit=%s\\n' " + shlex.quote(label) + " \"$status\" >> " +
                         shlex.quote(str(summary)))
            lines.append("echo " + shlex.quote("Log: " + str(logfile)))
        lines.append("echo " + shlex.quote("Summary: " + str(summary)))
        lines.append("read -r -p 'Press Enter to close...' _ || true")
        script.write_text("\n".join(lines) + "\n")
        script.chmod(0o700)
        self.write("Sequential dyld analysis: " + str(folder))
        terminal(script)

    def checksum(self):
        path = Path(self.iso.get()).expanduser()
        if not path.is_file():
            messagebox.showerror("ISO", "Choose an existing installer image")
            return
        def work():
            h = hashlib.sha256()
            with path.open("rb") as fh:
                for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                    h.update(chunk)
            folder = timestamped("iso-hash")
            (folder / "sha256.txt").write_text(h.hexdigest() + "  " + path.name + "\n")
            self.after(0, lambda: self.write("ISO SHA-256: " + h.hexdigest() + " (compare with vendor hash)"))
        threading.Thread(target=work, daemon=True).start()

    def os_plan(self):
        folder = timestamped("os-plan")
        text = (f"# {self.distro.get()} preparation\n\nISO: {self.iso.get() or 'not selected'}\n\n"
                "- [ ] Verify official ISO SHA-256\n"
                "- [ ] Back up any existing data\n"
                "- [ ] Verify T2Linux compatibility for this Mac\n"
                "- [ ] Resolve Activation Lock through authorized ownership channels\n"
                "- [ ] Verify external boot is allowed and Secure Boot permits the OS\n"
                "- [ ] Follow https://wiki.t2linux.org/ installer guidance\n\n"
                "Burning-iBridge does not flash an installer, change EFI policy, or erase disks.\n")
        (folder / "plan.md").write_text(text)
        self.write("Saved OS plan: " + str(folder / "plan.md"))

    def close(self):
        self.stop_proxy()
        self.destroy()


if __name__ == "__main__":
    BurningIBridge().mainloop()
