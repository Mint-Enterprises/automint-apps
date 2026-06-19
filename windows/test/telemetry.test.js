const test = require("node:test");
const assert = require("node:assert");
const { createTelemetry, QUEUE_CAP, SCHEMA } = require("../shared/telemetry");

function fakeStore(initial = {}) {
  const data = { ...initial };
  return {
    get: (k, d) => (k in data ? data[k] : d),
    set: (k, v) => {
      data[k] = v;
    },
    _data: data,
  };
}

const fp = {
  collect: () => ({
    deviceId: "dev-1",
    installId: "inst-1",
    appVersion: "1.0.0",
    electronVersion: "41",
    chromeVersion: "138",
    nodeVersion: "22",
    platform: "win32",
    arch: "x64",
    osVersion: "Win 11",
    locale: "en-US",
    timezone: "UTC",
    cpuCount: 8,
    totalMemMb: 16384,
    displayCount: 1,
    primaryDisplay: { width: 1920, height: 1080, scale: 1 },
  }),
  headers: () => ({ "X-Automint-Desktop": "1" }),
};

function deferred() {
  let resolve;
  const promise = new Promise((r) => (resolve = r));
  return { promise, resolve };
}

let idCounter = 0;
function makeTel(overrides = {}) {
  idCounter = 0;
  return createTelemetry({
    settingsStore: overrides.settingsStore || fakeStore(),
    queueStore: overrides.queueStore || fakeStore(),
    fingerprint: fp,
    netFetch: overrides.netFetch || (async () => ({ ok: true, status: 200 })),
    isOnline: overrides.isOnline || (() => false),
    now: overrides.now || (() => 1000),
    randomId: overrides.randomId || (() => `id-${++idCounter}`),
    endpoint: overrides.endpoint || "https://example.test/api/telemetry",
    log: () => {},
  });
}

module.exports = { fakeStore, fp, deferred, makeTel };

test("track() is a no-op when consent is off", () => {
  const t = makeTel();
  t.setConsent(false);
  t.track("pip.opened", {});
  assert.equal(t._snapshot().length, 0);
});

test("trackCore() enqueues regardless of consent", () => {
  const t = makeTel();
  t.setConsent(false);
  t.trackCore("app.heartbeat", {});
  const q = t._snapshot();
  assert.equal(q.length, 1);
  assert.equal(q[0].tier, 1);
});

test("track() enqueues a tier-2 event with an id when consent is on", () => {
  const t = makeTel();
  t.setConsent(true);
  t.track("pip.opened", { a: 1 });
  const q = t._snapshot();
  assert.equal(q.length, 1);
  assert.equal(q[0].tier, 2);
  assert.equal(q[0].name, "pip.opened");
  assert.ok(q[0].id, "event has an id");
  assert.deepEqual(q[0].props, { a: 1 });
});

test("enqueue shallow-clones props so later caller mutation cannot corrupt it", () => {
  const t = makeTel();
  const p = { a: 1 };
  t.track("x", p);
  p.a = 2;
  assert.equal(t._snapshot()[0].props.a, 1);
});

test("queue is capped at QUEUE_CAP, dropping oldest", () => {
  const t = makeTel();
  for (let i = 0; i < QUEUE_CAP + 50; i++) t.trackCore("app.heartbeat", { i });
  const q = t._snapshot();
  assert.equal(q.length, QUEUE_CAP);
  assert.equal(q[0].props.i, 50);
});

test("flush() posts the queue and clears it + mirror on a 2xx", async () => {
  const queueStore = fakeStore();
  let captured = null;
  const t = makeTel({
    queueStore,
    isOnline: () => true,
    netFetch: async (u, o) => {
      captured = { u, o };
      return { ok: true, status: 200 };
    },
  });
  t.track("pip.opened", {});
  await t.flush();
  assert.equal(t._snapshot().length, 0);
  assert.deepEqual(queueStore._data.telemetryQueue, []);
  assert.equal(captured.u, "https://example.test/api/telemetry");
  assert.equal(captured.o.redirect, "error");
  assert.equal(captured.o.headers["X-Automint-Desktop"], "1");
  assert.equal(captured.o.headers["Content-Type"], "application/json");
  const payload = JSON.parse(captured.o.body);
  assert.equal(payload.schema, SCHEMA);
  assert.equal(payload.deviceId, "dev-1");
  assert.deepEqual(payload.env.primaryDisplay, { width: 1920, height: 1080, scale: 1 });
  assert.equal(payload.events.length, 1);
  assert.ok(payload.events[0].id, "event id is sent");
});

test("flush() keeps the queue on a transient 5xx, then drains on a later 2xx", async () => {
  let ok = false;
  const t = makeTel({
    isOnline: () => true,
    netFetch: async () => (ok ? { ok: true, status: 200 } : { ok: false, status: 503 }),
  });
  t.track("pip.opened", {});
  await t.flush();
  assert.equal(t._snapshot().length, 1);
  ok = true;
  await t.flush();
  assert.equal(t._snapshot().length, 0);
});

test("flush() drops the batch on a permanent 4xx (poison)", async () => {
  const t = makeTel({
    isOnline: () => true,
    netFetch: async () => ({ ok: false, status: 400 }),
  });
  t.track("pip.opened", {});
  await t.flush();
  assert.equal(t._snapshot().length, 0);
});

test("flush() is skipped while offline", async () => {
  let called = false;
  const t = makeTel({
    isOnline: () => false,
    netFetch: async () => {
      called = true;
      return { ok: true, status: 200 };
    },
  });
  t.track("pip.opened", {});
  await t.flush();
  assert.equal(called, false);
  assert.equal(t._snapshot().length, 1);
});

test("flush() refuses a non-https endpoint", async () => {
  let called = false;
  const t = makeTel({
    endpoint: "http://insecure.test/api/telemetry",
    isOnline: () => true,
    netFetch: async () => {
      called = true;
      return { ok: true, status: 200 };
    },
  });
  t.track("pip.opened", {});
  await t.flush();
  assert.equal(called, false);
  assert.equal(t._snapshot().length, 1);
});

test("enqueue auto-flushes when the queue reaches BATCH_TRIGGER", async () => {
  let calls = 0;
  const { BATCH_TRIGGER } = require("../shared/telemetry");
  const t = makeTel({
    isOnline: () => true,
    netFetch: async () => {
      calls++;
      return { ok: true, status: 200 };
    },
  });
  for (let i = 0; i < BATCH_TRIGGER; i++) t.trackCore("app.heartbeat", { i });
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls, 1);
  assert.equal(t._snapshot().length, 0);
});

test("flush() is reentrancy-guarded — a second call while in flight fires once", async () => {
  const d = deferred();
  let calls = 0;
  const t = makeTel({
    isOnline: () => true,
    netFetch: async () => {
      calls++;
      await d.promise;
      return { ok: true, status: 200 };
    },
  });
  t.track("pip.opened", {});
  const f1 = t.flush();
  const f2 = t.flush();
  d.resolve();
  await Promise.all([f1, f2]);
  assert.equal(calls, 1);
  assert.equal(t._snapshot().length, 0);
});

test("flush() preserves events enqueued DURING an in-flight flush", async () => {
  const d = deferred();
  const t = makeTel({
    isOnline: () => true,
    netFetch: async () => {
      await d.promise;
      return { ok: true, status: 200 };
    },
  });
  t.track("first", {});
  const f = t.flush();
  t.track("second", {});
  d.resolve();
  await f;
  const q = t._snapshot();
  assert.equal(q.length, 1);
  assert.equal(q[0].name, "second");
});

test("init() is idempotent", () => {
  const queueStore = fakeStore({ telemetryQueue: [] });
  const t = makeTel({ queueStore });
  t.init();
  t.init();

  assert.equal(t._snapshot().length, 0);
});

test("init() loads a persisted queue from the queueStore", () => {
  const queueStore = fakeStore({
    telemetryQueue: [{ id: "x", tier: 1, name: "app.heartbeat", ts: 1, props: {} }],
  });
  const t = makeTel({ queueStore, settingsStore: fakeStore({ enableTelemetry: true }) });
  t.init();
  assert.equal(t._snapshot().length, 1);
  assert.equal(t._snapshot()[0].name, "app.heartbeat");
});

test("init() tolerates a corrupt (non-array) stored queue", () => {
  const queueStore = fakeStore({ telemetryQueue: "oops" });
  const t = makeTel({ queueStore });
  t.init();
  assert.deepEqual(t._snapshot(), []);
  t.trackCore("app.heartbeat", {});
  assert.equal(t._snapshot().length, 1);
});

test("init() reads consent=false and then suppresses tier-2", () => {
  const t = makeTel({ settingsStore: fakeStore({ enableTelemetry: false }) });
  t.init();
  t.track("pip.opened", {});
  assert.equal(t._snapshot().length, 0);
});

test("init() synthesizes a crashed session_end after an unclean shutdown", () => {
  const queueStore = fakeStore({ openSession: { startedAt: 1, clean: false } });
  const t = makeTel({ queueStore });
  t.init();
  const q = t._snapshot();
  assert.equal(q.length, 1);
  assert.equal(q[0].name, "app.session_end");
  assert.equal(q[0].props.crashed, true);
});

test("endSession() emits session_end with durationMs and marks openSession clean", async () => {
  let clock = 1000;
  const queueStore = fakeStore();
  const t = makeTel({ queueStore, now: () => clock });
  t.init();
  clock = 5000;
  await t.endSession();
  const ended = queueStore._data.openSession.clean;
  assert.equal(ended, true);

  const persisted = queueStore._data.telemetryQueue;
  const se = persisted.find((e) => e.name === "app.session_end");
  assert.ok(se);
  assert.equal(se.props.durationMs, 4000);
});

test("setConsent(false) purges pending tier-2 but keeps tier-1", () => {
  const t = makeTel();
  t.trackCore("app.heartbeat", {});
  t.track("pip.opened", {});
  assert.equal(t._snapshot().length, 2);
  t.setConsent(false);
  const q = t._snapshot();
  assert.equal(q.length, 1);
  assert.equal(q[0].tier, 1);
});

test("flushPending re-arms a flush when events remain after the first drains", async () => {
  const d = deferred();
  let calls = 0;
  const t = makeTel({
    isOnline: () => true,
    netFetch: async () => {
      calls++;
      await d.promise;
      return { ok: true, status: 200 };
    },
  });
  t.track("a", {});
  const f1 = t.flush();
  t.flush();
  t.track("b", {});
  d.resolve();
  await f1;

  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls, 2);
  assert.equal(t._snapshot().length, 0);
});

test("a mid-flight opt-out keeps tier-2 out of the transmitted batch", async () => {
  const d = deferred();
  const payloads = [];
  const t = makeTel({
    isOnline: () => true,
    netFetch: async (_u, o) => {
      payloads.push(JSON.parse(o.body));
      await d.promise;
      return { ok: true, status: 200 };
    },
  });
  t.trackCore("app.heartbeat", {});
  t.track("pip.opened", {});
  const f = t.flush();
  d.resolve();
  await f;

  t.trackCore("app.heartbeat", {});
  t.track("should.not.send", {});
  t.setConsent(false);
  await t.flush();
  const last = payloads[payloads.length - 1];
  assert.ok(
    last.events.every((e) => e.name !== "pip.opened" && e.name !== "should.not.send"),
    "no tier-2 events transmitted after opt-out",
  );
});

test("flush() makes no request when the only queued events are tier-2 and consent is off", async () => {

  const queueStore = fakeStore({
    telemetryQueue: [{ id: "old", tier: 2, name: "pip.opened", ts: 1, props: {} }],
  });
  let called = false;
  const t = makeTel({
    queueStore,
    settingsStore: fakeStore({ enableTelemetry: false }),
    isOnline: () => true,
    netFetch: async () => {
      called = true;
      return { ok: true, status: 200 };
    },
  });
  t.init();
  assert.equal(t._snapshot().length, 1);
  await t.flush();
  assert.equal(called, false);
  assert.equal(t._snapshot().length, 1);
});
