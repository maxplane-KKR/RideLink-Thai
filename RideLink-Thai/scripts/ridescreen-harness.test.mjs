import test from "node:test";
import assert from "node:assert/strict";
import { evaluateJudgments, reviewState, reviewWithJev, verifySuite } from "./ridescreen-harness.mjs";

const modelNames = ["150X", "350D", "350E", "368D", "368E", "368G", "368K"];
const source = `internal enum class ThaiZontesModel(val label: String) {\n${modelNames.map((m) => `    Z${m}("Zontes ${m}"),`).join("\n")}\n}`;
const resources = '<string name="ridescreen_live_explanation">ภาพฉาย ไม่เขียนธีมโรงงาน</string><string name="ridescreen_live_unknown">RPM —</string><string name="ridescreen_live_style_hint">ภาพตัวอย่างไม่มีข้อมูลจริงจากรถ</string>';
const tests = {
  ThaiZontesModelsTest: ["knownThaiQrModelsUseRideScreenCanvas", "threeThai368ChoicesUseTheApkFallback", "observed150xDoesNotInheritThe368Canvas", "observedCanvasOverridesDefaultsAndQrOverridesThePicker", "modelListStaysWithinThaiScope", "qrSelectionUsesKnownProfilesAndKeeps150x", "androidAutoUsesCurrentBikePanelInsteadOfPreviousBike"],
  RideScreenTelemetryTest: ["missingAndStaleGpsNeverBecomeSampleSpeed", "freshGpsIsConvertedAndStandstillJitterIsBounded", "liveFrameContainsOnlyFreshGpsAndNoSampleVehicleData"],
  LiveDashboardSettingsTest: ["liveStyleAndEmptyFieldSelectionSurviveReopen", "unknownPreferencesFallBackAndFieldsAreAllowlisted"],
  SelectedDashboardLayoutTest: ["missingDataNeverCreatesVehicleReadings", "selectedFieldsAreRenderedOnceAndRacingUsesItsTopRpmGauge", "cardsFitWithoutOverlappingAtEverySupportedCount"],
};
function report(name, names = tests[name]) {
  const suite = `dev.zanderp.opencfmoto.${name}`;
  return `<testsuite name="${suite}" tests="${names.length}" failures="0" errors="0" skipped="0">${names.map((n) => `<testcase name="${n}" classname="${suite}"/>`).join("")}</testsuite>`;
}
const reports = Object.fromEntries(Object.keys(tests).map((name) => [name, report(name)]));

test("JUnit rejects missing and failed scenarios", () => {
  assert.equal(verifySuite(reports.RideScreenTelemetryTest, "RideScreenTelemetryTest").length, 3);
  assert.throws(() => verifySuite(report("RideScreenTelemetryTest", tests.RideScreenTelemetryTest.slice(0, 1)), "RideScreenTelemetryTest"));
  assert.throws(() => verifySuite(reports.RideScreenTelemetryTest.replace('failures="0"', 'failures="1"'), "RideScreenTelemetryTest"));
});

test("state uses only Thai models and rejects added models", () => {
  assert.deepEqual(reviewState(source, resources, reports).models, modelNames);
  assert.throws(() => reviewState(source.replace("\n}", '\n    Z125X("Zontes 125X"),\n}'), resources, reports));
});

test("Jev result must contain valid probabilities and low scores need review", () => {
  const answers = { projectionScope: { type: "noul", noul: 0.99 }, unavailableData: { type: "noul", noul: 0.9 }, thaiScope: { type: "noul", noul: 0.7 }, previewScope: { type: "noul", noul: 0.9 } };
  assert.equal(evaluateJudgments(answers).status, "review");
  assert.throws(() => evaluateJudgments({ ...answers, thaiScope: { type: "noul", noul: 2 } }));
  assert.throws(() => evaluateJudgments({}));
});

test("Jev request batches questions without sending local raw data", async () => {
  const state = reviewState(source, resources, reports);
  let captured;
  const fetcher = async (url, options) => {
    captured = { url, body: JSON.parse(options.body) };
    return { ok: true, json: async () => ({ answers: Object.fromEntries(["projectionScope", "unavailableData", "thaiScope", "previewScope"].map((key) => [key, { type: "noul", noul: 0.95 }])) }) };
  };
  assert.equal((await reviewWithJev(state, "synthetic-key", fetcher)).status, "pass");
  assert.equal(captured.url, "https://api.typesafe.ai/v1/systemone");
  assert.deepEqual(Object.keys(captured.body.questions), ["projectionScope", "unavailableData", "thaiScope", "previewScope"]);
  assert.equal(captured.body.state.evidenceScope.includes("รถจริง"), true);
  assert.equal(JSON.stringify(captured.body).includes("synthetic-key"), false);
  await assert.rejects(() => reviewWithJev(state, "", fetcher));
});
