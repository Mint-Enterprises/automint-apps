const splashApi = window.splash;

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

let websiteLoaded = false;
let websiteFailed = false;

const websiteReady = new Promise((resolve) => {
  splashApi.onWebsiteReady(() => {
    websiteLoaded = true;
    resolve();
  });
});

const websiteFailedPromise = new Promise((resolve) => {
  splashApi.onWebsiteFailed((errorCode, errorDesc) => {
    websiteFailed = true;
    resolve({ errorCode, errorDesc });
  });
});

const splashContent = document.getElementById("splash-content");
const errorContainer = document.getElementById("error-container");
const errorTitle = document.getElementById("error-title");
const retryBtn = document.getElementById("error-retry");

function showError(message) {
  splashContent.style.display = "none";
  errorTitle.textContent = message || "Unable to connect";
  errorContainer.classList.remove("hidden");
}

retryBtn.addEventListener("click", () => {
  errorContainer.classList.add("hidden");
  splashContent.style.display = "flex";
  websiteLoaded = false;
  websiteFailed = false;

  splashApi.retry();
  runProgress();
});

const statusEl = document.getElementById("splash-status");

const STATUS_STAGES = [
  { at: 3500,  text: "Connecting to Automint…" },
  { at: 8000,  text: "Still loading — slow connection detected" },
  { at: 15000, text: "Almost there — large download in progress" },
  { at: 25000, text: "Hang tight, finishing setup…" },
];

let statusTimers = [];

function setStatus(text) {
  if (!statusEl) return;
  statusEl.textContent = text;
  statusEl.classList.add("is-visible");
}

function clearStatus() {
  for (const id of statusTimers) clearTimeout(id);
  statusTimers = [];
  if (statusEl) {
    statusEl.classList.remove("is-visible");
    statusEl.textContent = "";
  }
}

function scheduleStatusEscalation() {
  clearStatus();
  for (const { at, text } of STATUS_STAGES) {
    statusTimers.push(setTimeout(() => setStatus(text), at));
  }
}

const RING_CIRCUMFERENCE = 2 * Math.PI * 46;

function setProgress(pct) {
  const ring = document.querySelector(".ring-fill");
  if (!ring) return;
  const clamped = Math.max(0, Math.min(100, pct));
  const offset = RING_CIRCUMFERENCE * (1 - clamped / 100);
  ring.style.strokeDashoffset = String(offset);
}

async function runProgress() {
  setProgress(0);
  scheduleStatusEscalation();

  await sleep(120);
  setProgress(35);

  let crawl = 35;
  let settled = false;
  const TIMEOUT_MS = 45000;

  const timeoutId = setTimeout(() => {
    if (!settled) {
      settled = true;
      clearStatus();
      showError("Connection timed out");
    }
  }, TIMEOUT_MS);

  websiteReady.then(() => {
    if (!settled) {
      settled = true;
      clearTimeout(timeoutId);
      clearStatus();
    }
  });

  websiteFailedPromise.then(() => {
    if (!settled) {
      settled = true;
      clearTimeout(timeoutId);
      clearStatus();
      showError("Unable to connect");
    }
  });

  while (!settled && crawl < 90) {
    await sleep(420);
    if (settled) break;
    crawl += 1;
    setProgress(crawl);
  }

  if (!websiteLoaded) return;

  setProgress(100);
  await sleep(380);

  const wrapper = document.getElementById("splash-wrapper");
  wrapper.classList.add("fade-out");
  await new Promise((resolve) => {
    wrapper.addEventListener("animationend", resolve, { once: true });
  });

  splashApi.done();
}

runProgress();
