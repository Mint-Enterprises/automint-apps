const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const configPath = path.join(__dirname, "deploy.config.js");
if (!fs.existsSync(configPath)) {
  console.error("Missing scripts/deploy.config.js");
  console.error("Copy deploy.config.example.js and fill in your VPS details.");
  process.exit(1);
}

const { VPS_HOST, VPS_USER, VPS_PATH, SSH_KEY } = require("./deploy.config");

const distDir = path.join(__dirname, "..", "dist");
const version = require("../package.json").version;

const files = fs
  .readdirSync(distDir)
  .filter(
    (f) =>
      f === "latest.yml" ||
      f === "latest-mac.yml" ||
      f === "latest-linux.yml" ||
      f === "integrity.json" ||
      f.endsWith(".exe") ||
      f.endsWith(".dmg") ||
      f.endsWith(".AppImage") ||
      f.endsWith(".blockmap"),
  );

if (files.length === 0) {
  console.error(
    "No release files found in dist/. Run 'npm run build:win' first.",
  );
  process.exit(1);
}

console.log(`\nDeploying Automint v${version}`);
console.log(
  `Uploading ${files.length} files to ${VPS_USER}@${VPS_HOST}:${VPS_PATH}\n`,
);

const sshFlag = SSH_KEY ? `-i "${SSH_KEY}"` : "";
const scpBase = `scp ${sshFlag}`;
const sshBase = `ssh ${sshFlag} ${VPS_USER}@${VPS_HOST}`;

try {
  execSync(`${sshBase} "mkdir -p ${VPS_PATH}"`, { stdio: "inherit" });
} catch {
  console.error("Failed to connect to VPS. Check your SSH config.");
  process.exit(1);
}

for (const file of files) {
  const filePath = path.join(distDir, file);
  console.log(`  Uploading ${file}...`);
  try {
    execSync(
      `${scpBase} "${filePath}" ${VPS_USER}@${VPS_HOST}:"${VPS_PATH}/"`,
      {
        stdio: "inherit",
      },
    );
  } catch {
    console.error(`  Failed to upload ${file}`);
    process.exit(1);
  }
}

console.log(`\nDone! v${version} is live on ${VPS_HOST}`);
