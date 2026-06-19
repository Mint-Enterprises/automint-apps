

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

function gitOrEmpty(...args) {
  try {
    return execFileSync("git", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return "";
  }
}

const repoRoot = path.join(__dirname, "..");
const buildDir = path.join(repoRoot, "build");
const outPath = path.join(buildDir, "provenance.json");

if (!fs.existsSync(buildDir)) {
  fs.mkdirSync(buildDir, { recursive: true });
}

const pkg = JSON.parse(
  fs.readFileSync(path.join(repoRoot, "package.json"), "utf8"),
);

const commit = gitOrEmpty("rev-parse", "HEAD");
const shortCommit = gitOrEmpty("rev-parse", "--short", "HEAD");
const branch = gitOrEmpty("rev-parse", "--abbrev-ref", "HEAD");

const dirty =
  gitOrEmpty("status", "--porcelain", "--untracked-files=no").length > 0;

const provenance = {
  version: pkg.version,
  commit,
  shortCommit,
  branch,
  dirty,
  builtAt: new Date().toISOString(),
};

fs.writeFileSync(outPath, JSON.stringify(provenance, null, 2) + "\n");
console.log("Provenance written:", outPath);
console.log(JSON.stringify(provenance, null, 2));
