

const SCHEMA = 1;
const QUEUE_CAP = 500;
const BATCH_TRIGGER = 20;

function createTelemetry(deps) {
  const {
    settingsStore,
    queueStore,
    fingerprint,
    netFetch,
    isOnline,
    now,
    randomId,
    endpoint,
    log,
  } = deps;

  let queue = [];
  let consent = true;
  let sessionId = randomId();
  let sessionStart = now();
  let flushing = false;
  let flushPending = false;
  let initialized = false;

  function persist() {
    try {
      queueStore.set("telemetryQueue", queue);
    } catch (err) {
      log("telemetry", "persist failed:", err);
    }
  }

  function enqueue(tier, name, props) {
    const wasBelowBatch = queue.length < BATCH_TRIGGER;
    queue.push({
      id: randomId(),
      tier,
      name,
      ts: now(),
      props: props ? { ...props } : {},
    });
    if (queue.length > QUEUE_CAP) {
      queue.splice(0, queue.length - QUEUE_CAP);
    }
    persist();

    if (wasBelowBatch && queue.length >= BATCH_TRIGGER) {
      flush();
    }
  }

  function track(name, props) {
    if (!consent) return;
    enqueue(2, name, props);
  }

  function trackCore(name, props) {
    enqueue(1, name, props);
  }

  function setConsent(enabled) {
    consent = !!enabled;
    if (!consent) {
      queue = queue.filter((e) => e.tier !== 2);
      persist();
    }
  }

  function init() {
    if (initialized) return;
    initialized = true;
    try {
      const saved = queueStore.get("telemetryQueue", []);

      if (Array.isArray(saved)) {
        queue = saved.filter((e) => e && typeof e.tier === "number");
      }
    } catch (err) {
      log("telemetry", "queue load failed:", err);
    }
    consent = settingsStore.get("enableTelemetry", true) !== false;
    let crashed = false;
    let prevStartedAt = null;
    try {
      const prev = queueStore.get("openSession", null);
      if (prev && prev.clean === false) {
        crashed = true;
        prevStartedAt = typeof prev.startedAt === "number" ? prev.startedAt : null;
      }
    } catch (err) {
      log("telemetry", "openSession read failed:", err);
    }
    sessionId = randomId();
    sessionStart = now();
    try {
      queueStore.set("openSession", { startedAt: sessionStart, clean: false });
    } catch (err) {
      log("telemetry", "openSession write failed:", err);
    }
    if (crashed) {

      trackCore("app.session_end", { crashed: true, prevStartedAt });
    }
  }

  function endSession() {
    trackCore("app.session_end", { durationMs: now() - sessionStart });
    try {
      queueStore.set("openSession", { startedAt: sessionStart, clean: true });
    } catch (err) {
      log("telemetry", "openSession clean write failed:", err);
    }
    return flush();
  }

  function buildPayload(events) {
    const fp = fingerprint.collect();
    const pd = fp.primaryDisplay;
    return {
      schema: SCHEMA,
      deviceId: fp.deviceId,
      installId: fp.installId,
      sentAt: now(),
      app: {
        version: fp.appVersion,
        electron: fp.electronVersion,
        chrome: fp.chromeVersion,
        node: fp.nodeVersion,
      },
      env: {
        platform: fp.platform,
        arch: fp.arch,
        osVersion: fp.osVersion,
        locale: fp.locale,
        timezone: fp.timezone,
        cpuCount: fp.cpuCount,
        totalMemMb: fp.totalMemMb,
        displayCount: fp.displayCount,
        primaryDisplay: pd
          ? { width: pd.width, height: pd.height, scale: pd.scale }
          : null,
      },
      session: { id: sessionId, startedAt: sessionStart },
      events: events.map((e) => ({
        id: e.id,
        name: e.name,
        ts: e.ts,
        props: e.props,
      })),
    };
  }

  function isPermanent(status) {
    return status >= 400 && status < 500 && status !== 408 && status !== 429;
  }

  async function flush() {
    if (flushing) {
      flushPending = true;
      return;
    }
    if (!queue.length) return;
    if (!isOnline()) return;
    if (typeof endpoint !== "string" || !endpoint.startsWith("https:")) {
      log("telemetry", "refusing non-https endpoint:", endpoint);
      return;
    }

    const batch = queue.filter((e) => e.tier === 1 || consent);
    if (!batch.length) return;
    flushing = true;
    try {
      const res = await netFetch(endpoint, {
        method: "POST",
        redirect: "error",
        headers: {
          "Content-Type": "application/json",
          ...fingerprint.headers(),
        },
        body: JSON.stringify(buildPayload(batch)),
      });
      if (res && res.ok) {
        const sent = new Set(batch);
        queue = queue.filter((e) => !sent.has(e));
        persist();
      } else if (res && isPermanent(res.status)) {
        const sent = new Set(batch);
        queue = queue.filter((e) => !sent.has(e));
        persist();
        log("telemetry", "dropped batch on permanent status:", res.status);
      } else {
        log("telemetry", "flush transient failure:", res && res.status);
      }
    } catch (err) {
      log("telemetry", "flush failed:", err);
    } finally {
      flushing = false;
      if (flushPending) {
        flushPending = false;
        if (queue.length) flush();
      }
    }
  }

  return {
    init,
    track,
    trackCore,
    setConsent,
    flush,
    endSession,
    _snapshot: () => queue.slice(),
  };
}

module.exports = { createTelemetry, SCHEMA, QUEUE_CAP, BATCH_TRIGGER };
