const sharp = require("sharp");
const png2icons = require("png2icons");
const fs = require("fs");
const path = require("path");

const SOURCE = path.join(__dirname, "..", "icons", "source.png");
const OUT = path.join(__dirname, "..", "icons");

async function generate() {
  const buf = fs.readFileSync(SOURCE);

  await sharp(buf).resize(256, 256).toFile(path.join(OUT, "icon.png"));
  await sharp(buf).resize(128, 128).toFile(path.join(OUT, "128x128.png"));
  await sharp(buf).resize(32, 32).toFile(path.join(OUT, "32x32.png"));

  const ico = png2icons.createICO(buf, png2icons.HERMITE, 0, true, true);
  fs.writeFileSync(path.join(OUT, "icon.ico"), ico);

  const icns = png2icons.createICNS(buf, png2icons.HERMITE, 0);
  fs.writeFileSync(path.join(OUT, "icon.icns"), icns);

  console.log("Icons generated successfully.");
}

generate().catch(console.error);
