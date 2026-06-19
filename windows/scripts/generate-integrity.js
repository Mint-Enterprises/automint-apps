

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const DIST = path.join(__dirname, "..", "dist");

const ASAR_PATHS = {
  win32: path.join(DIST, "win-unpacked", "resources", "app.asar"),
  darwin: path.join(
    DIST,
    "mac",
    "Automint.app",
    "Contents",
    "Resources",
    "app.asar",
  ),
  linux: path.join(DIST, "linux-unpacked", "resources", "app.asar"),
};

function hashFile(filePath) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(filePath)) {
      resolve(null);
      return;
    }
    const sha = crypto.createHash("sha256");
    const stream = fs.createReadStream(filePath);
    stream.on("data", (chunk) => sha.update(chunk));
    stream.on("end", () => resolve(sha.digest("hex")));
    stream.on("error", reject);
  });
}

async function main() {
  const integrity = {};

  for (const [platform, asarPath] of Object.entries(ASAR_PATHS)) {
    const hash = await hashFile(asarPath);
    if (hash) {
      integrity[platform] = hash;
      console.log(`  ${platform}: ${hash}`);
    } else {
      console.log(`  ${platform}: not found (skipped)`);
    }
  }

  if (Object.keys(integrity).length === 0) {
    console.error("No asar files found. Run electron-builder first.");
    process.exit(1);
  }

  const outPath = path.join(DIST, "integrity.json");
  fs.writeFileSync(outPath, JSON.stringify(integrity, null, 2) + "\n");
  console.log(`\nWritten to ${outPath}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
