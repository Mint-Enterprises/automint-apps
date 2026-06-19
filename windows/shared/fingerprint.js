

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execFileSync } = require("child_process");
const { app, screen, powerMonitor } = require("electron");

const HMAC_KEY = "automint-desktop-v1";

let cached = null;

function sha256(s) {
  return crypto.createHash("sha256").update(s).digest("hex");
}

function safe(fn, fallback = null) {
  try {
    return fn();
  } catch {
    return fallback;
  }
}

function readWindowsMachineGuid() {

  try {
    const out = execFileSync(
      "reg",
      ["query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"],
      { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], timeout: 1500 },
    );
    const m = out.match(/MachineGuid\s+REG_SZ\s+([0-9a-fA-F-]+)/);
    return m ? m[1].trim() : null;
  } catch {
    return null;
  }
}

function readMacPlatformUuid() {
  try {
    const out = execFileSync(
      "ioreg",
      ["-rd1", "-c", "IOPlatformExpertDevice"],
      { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], timeout: 1500 },
    );
    const m = out.match(/"IOPlatformUUID"\s*=\s*"([^"]+)"/);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

function readLinuxMachineId() {
  for (const p of ["/etc/machine-id", "/var/lib/dbus/machine-id"]) {
    try {
      const v = fs.readFileSync(p, "utf8").trim();
      if (v) return v;
    } catch {

    }
  }
  return null;
}

function nativeMachineId() {
  if (process.platform === "win32") return readWindowsMachineGuid();
  if (process.platform === "darwin") return readMacPlatformUuid();
  return readLinuxMachineId();
}

function macHash() {
  const macs = [];
  const ifaces = safe(() => os.networkInterfaces(), {});
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name] || []) {
      if (
        iface.mac &&
        iface.mac !== "00:00:00:00:00:00" &&
        !iface.internal
      ) {
        macs.push(iface.mac.toLowerCase());
      }
    }
  }
  if (!macs.length) return null;
  return sha256(macs.sort().join("|"));
}

function readOrCreateInstallId() {
  const file = path.join(app.getPath("userData"), ".install-id");
  try {
    const existing = fs.readFileSync(file, "utf8").trim();
    if (existing) return existing;
  } catch {

  }
  const id = crypto.randomUUID();
  try {
    fs.writeFileSync(file, id, "utf8");
  } catch {

  }
  return id;
}

function deviceIdFromMachine() {

  const machine = nativeMachineId();
  const mac = macHash();
  const fallback = readOrCreateInstallId();
  const seed = machine || mac || fallback;
  const h = sha256(`automint-desktop|${seed}`);

  return [
    h.slice(0, 8),
    h.slice(8, 12),
    "4" + h.slice(13, 16),
    "a" + h.slice(17, 20),
    h.slice(20, 32),
  ].join("-");
}

function collect() {
  if (cached) return cached;

  const machineRaw = nativeMachineId();
  const installId = readOrCreateInstallId();
  const deviceId = deviceIdFromMachine();

  const displays = safe(() => screen.getAllDisplays(), []);
  const primary = safe(() => screen.getPrimaryDisplay(), null);
  const cpus = safe(() => os.cpus(), []);

  cached = {
    deviceId,
    installId,

    machineIdHash: machineRaw ? sha256(machineRaw) : null,
    macHash: macHash(),
    hostnameHash: safe(() => {
      const h = os.hostname();
      return h ? sha256(h) : null;
    }),
    usernameHash: safe(() => {
      const u = os.userInfo().username;
      return u ? sha256(u) : null;
    }),
    platform: process.platform,
    arch: process.arch,
    osType: safe(() => os.type()),
    osRelease: safe(() => os.release()),
    osVersion: safe(() => os.version()),
    cpuModel: cpus[0]?.model ?? null,
    cpuCount: cpus.length || null,
    cpuSpeedMhz: cpus[0]?.speed ?? null,
    totalMemMb: safe(() => Math.round(os.totalmem() / (1024 * 1024))),
    locale: safe(() => app.getLocale()),
    systemLocale: safe(() => app.getSystemLocale()),
    preferredLanguages: safe(() => app.getPreferredSystemLanguages?.()),
    timezone: safe(() => Intl.DateTimeFormat().resolvedOptions().timeZone),
    onBattery: safe(() => powerMonitor.isOnBatteryPower()),
    displayCount: displays.length,
    primaryDisplay: primary
      ? {
          width: primary.size?.width ?? null,
          height: primary.size?.height ?? null,
          scale: primary.scaleFactor ?? null,
          rotation: primary.rotation ?? null,
          colorDepth: primary.colorDepth ?? null,
          colorSpace: primary.colorSpace ?? null,
        }
      : null,
    displays: displays.map((d) => ({
      width: d.size?.width ?? null,
      height: d.size?.height ?? null,
      scale: d.scaleFactor ?? null,
      rotation: d.rotation ?? null,
      colorDepth: d.colorDepth ?? null,
      primary: primary ? d.id === primary.id : null,
    })),
    appVersion: safe(() => app.getVersion()),
    electronVersion: process.versions.electron ?? null,
    chromeVersion: process.versions.chrome ?? null,
    nodeVersion: process.versions.node ?? null,
    collectedAt: Date.now(),
  };
  return cached;
}

function sign(deviceId, installId, timestamp) {
  return crypto
    .createHmac("sha256", HMAC_KEY)
    .update(`${deviceId}|${installId}|${timestamp}`)
    .digest("hex");
}

function headers() {
  const fp = collect();
  const ts = String(Date.now());
  return {
    "X-Automint-Desktop": "1",
    "X-Automint-Desktop-Version": fp.appVersion ?? "",
    "X-Automint-Desktop-Device-Id": fp.deviceId,
    "X-Automint-Desktop-Install-Id": fp.installId,
    "X-Automint-Desktop-Machine-Hash": fp.machineIdHash ?? "",
    "X-Automint-Desktop-Mac-Hash": fp.macHash ?? "",
    "X-Automint-Desktop-Hostname-Hash": fp.hostnameHash ?? "",
    "X-Automint-Desktop-Platform": fp.platform,
    "X-Automint-Desktop-Arch": fp.arch,
    "X-Automint-Desktop-Timestamp": ts,
    "X-Automint-Desktop-Sig": sign(fp.deviceId, fp.installId, ts),
  };
}

module.exports = {
  collect,
  headers,
  deviceIdFromMachine,
  readOrCreateInstallId,
  HMAC_KEY,
};
