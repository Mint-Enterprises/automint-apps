const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 3456;
const RELEASES_DIR = path.join(__dirname, "releases");

const MIME_TYPES = {
  ".yml": "text/yaml",
  ".yaml": "text/yaml",
  ".json": "application/json",
  ".exe": "application/octet-stream",
  ".dmg": "application/octet-stream",
  ".AppImage": "application/octet-stream",
  ".blockmap": "application/octet-stream",
};

const ALLOWED_EXTENSIONS = new Set(Object.keys(MIME_TYPES));

const server = http.createServer((req, res) => {

  if (req.method !== "GET") {
    res.writeHead(405);
    res.end("Method Not Allowed");
    return;
  }

  const rawPath = req.url.split("?")[0].split("#")[0];

  if (rawPath.includes("\0") || rawPath.includes("%00")) {
    res.writeHead(400);
    res.end("Bad Request");
    return;
  }

  const safePath = path.normalize(rawPath).replace(/^(\.\.[/\\])+/, "");
  const filePath = path.join(RELEASES_DIR, safePath);

  if (
    !filePath.startsWith(RELEASES_DIR + path.sep) &&
    filePath !== RELEASES_DIR
  ) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  const ext = path.extname(filePath);
  if (!ALLOWED_EXTENSIONS.has(ext)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404);
      res.end("Not Found");
      return;
    }

    const contentType = MIME_TYPES[ext];

    res.writeHead(200, {
      "Content-Type": contentType,
      "Content-Length": stats.size,
      "X-Content-Type-Options": "nosniff",
    });

    fs.createReadStream(filePath).pipe(res);
  });
});

server.listen(PORT, () => {
  console.log(`Automint update server running on port ${PORT}`);
  console.log(`Serving releases from ${RELEASES_DIR}`);
});
