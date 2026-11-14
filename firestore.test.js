const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-meridian-cedc0ec6-6cca-46f5-867e-da5993495544";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validRiskConfig(uid) {
  const now = new Date();
  return {
    userId: uid,
    accountBalanceUsd: 10000.0,
    riskPerTradePct: 0.5,
    maxOpenRiskPct: 3.0,
    maxClusterRiskPct: 1.5,
    maxOpenPerInstrument: 1,
    dailyLossLimitPct: 2.0,
    weeklyLossLimitPct: 4.0,
    drawdownHalvePct: 5.0,
    drawdownHaltPct: 8.0,
    maxCostToStopRatio: 0.15,
    minLiquidationStops: 3,
    newsBlackoutBeforeMin: 30,
    newsBlackoutAfterMin: 15,
    feedMaxLagSeconds: 60,
    manualKillSwitch: false,
    createdAt: now,
    updatedAt: now,
  };
}

function validSignal(uid, overrides = {}) {
  const now = new Date();
  return {
    userId: uid,
    signalUuid: "sig-uuid-1042-abcd",
    createdAtIso: "2026-11-14T08:15:00Z",
    instrument: "EURUSD",
    assetClass: "fx",
    direction: "long",
    strategy: "S2_SESSION_BREAKOUT",
    strategyVersion: "1.0.0",
    configHash: "cfg_91a2b3",
    timeframe: "15m",
    regime: "LOW_VOL",
    conviction: 0.64,
    entryType: "zone",
    entryLow: 1.0842,
    entryHigh: 1.0848,
    stopLoss: 1.0815,
    tp1Price: 1.0890,
    tp1R: 1.5,
    tp1ClosePct: 50,
    tp2Price: 1.0935,
    tp2R: 3.0,
    tp2ClosePct: 50,
    suggestedRiskPct: 0.5,
    estCostR: 0.06,
    expiresAtIso: "2026-11-14T21:00:00Z",
    invalidation: "Close back inside the Asian range",
    rationale: "London open breakout above compressed Asian range (0.48 ATR).",
    riskStatus: "APPROVED",
    riskAdjustments: [],
    riskReasons: [],
    idempotencyKey: "S2_SESSION_BREAKOUT|EURUSD|2026-11-14T08:15:00Z",
    executionMode: "PAPER",
    status: "PENDING",
    realizedR: 0.0,
    maeR: 0.0,
    mfeR: 0.0,
    durationMinutes: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validSignalEvent(uid) {
  return {
    userId: uid,
    signalId: "sig_1042",
    sequenceIndex: 0,
    eventType: "SIGNAL_CREATED",
    timestampIso: "2026-11-14T08:15:00Z",
    payloadSummary: "EURUSD LONG S2_SESSION_BREAKOUT APPROVED",
    prevHash: "0".repeat(64),
    eventHash: "a".repeat(64),
    createdAt: new Date(),
  };
}

function validValidationReport(uid) {
  const now = new Date();
  return {
    userId: uid,
    strategyId: "S1_TREND_DONCHIAN",
    strategyVersion: "1.0.0",
    configHash: "cfg_s1_v1",
    dataSnapshotId: "snap_2026_11",
    randomSeed: 42,
    lifecycleStatus: "PAPER",
    trialCount: 3,
    oosTrades: 112,
    netExpectancyR: 0.24,
    profitFactor: 1.42,
    sharpe: 0.88,
    deflatedSharpe: 0.71,
    pbo: 0.18,
    mc95DrawdownPct: 4.1,
    ruinProbPct: 0.2,
    positiveYearsPct: 75.0,
    causalityPassed: true,
    beatRandom95th: true,
    failureReasons: [],
    equityCurveR: [0.0, 1.2, -0.5, 2.8],
    createdAt: now,
    updatedAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or write signals or events", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("signals").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig1").set(validSignal(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("signal_events").doc("ev1").set(validSignalEvent(ALICE_UID))
  );
});

test("Owner can create and read risk_config, signals, signal_events, and strategy_reports", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const aliceRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(aliceRef.collection("risk_config").doc("default").set(validRiskConfig(ALICE_UID)));
  await assertSucceeds(aliceRef.collection("signals").doc("sig_1042").set(validSignal(ALICE_UID)));
  await assertSucceeds(aliceRef.collection("signal_events").doc("ev_0").set(validSignalEvent(ALICE_UID)));
  await assertSucceeds(aliceRef.collection("strategy_reports").doc("S1_TREND_DONCHIAN").set(validValidationReport(ALICE_UID)));

  await assertSucceeds(aliceRef.collection("signals").get());
  await assertSucceeds(aliceRef.collection("signal_events").get());
});

test("Cross-user access is strictly denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_1042").set(validSignal(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore(DATABASE_ID);
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_1042").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("signals").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_bob").set(validSignal(BOB_UID))
  );
});

test("Append-only signal_events forbid update and delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const evRef = aliceDb.collection("users").doc(ALICE_UID).collection("signal_events").doc("ev_0");
  await assertSucceeds(evRef.set(validSignalEvent(ALICE_UID)));
  await assertFails(evRef.update({ payloadSummary: "tampered" }));
  await assertFails(evRef.delete());
});

test("Signal lifecycle blocks tampering immutable fields and blocks updates after terminal status", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const sigRef = aliceDb.collection("users").doc(ALICE_UID).collection("signals").doc("sig_1042");
  await assertSucceeds(sigRef.set(validSignal(ALICE_UID)));

  // Reject immutable stopLoss modification
  await assertFails(sigRef.update({ stopLoss: 1.0700 }));

  // Reject ghost field
  await assertFails(sigRef.update({ ghostField: true }));

  // Allow valid outcome transition to WIN
  await assertSucceeds(
    sigRef.update({
      status: "WIN",
      realizedR: 2.19,
      maeR: -0.32,
      mfeR: 2.85,
      durationMinutes: 310,
      updatedAt: new Date(),
    })
  );

  // Terminal state lock: subsequent update must fail
  await assertFails(
    sigRef.update({
      realizedR: 5.0,
      updatedAt: new Date(),
    })
  );

  // Delete forbidden
  await assertFails(sigRef.delete());
});
