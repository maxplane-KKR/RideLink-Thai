import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const models = ["150X", "350D", "350E", "368D", "368E", "368G", "368K"];
const suites = {
  ThaiZontesModelsTest: [
    "knownThaiQrModelsUseRideScreenCanvas", "threeThai368ChoicesUseTheApkFallback",
    "observed150xDoesNotInheritThe368Canvas", "observedCanvasOverridesDefaultsAndQrOverridesThePicker",
    "modelListStaysWithinThaiScope", "qrSelectionUsesKnownProfilesAndKeeps150x",
    "androidAutoUsesCurrentBikePanelInsteadOfPreviousBike",
  ],
  RideScreenTelemetryTest: ["missingAndStaleGpsNeverBecomeSampleSpeed", "freshGpsIsConvertedAndStandstillJitterIsBounded", "liveFrameContainsOnlyFreshGpsAndNoSampleVehicleData"],
  LiveDashboardSettingsTest: ["liveStyleAndEmptyFieldSelectionSurviveReopen", "unknownPreferencesFallBackAndFieldsAreAllowlisted"],
  SelectedDashboardLayoutTest: ["missingDataNeverCreatesVehicleReadings", "selectedFieldsAreRenderedOnceAndRacingUsesItsTopRpmGauge", "cardsFitWithoutOverlappingAtEverySupportedCount"],
};
const questions = {
  projectionScope: {
    type: "noul",
    instructions: "Does state.labels.explanation tell the rider that the live dashboard is projected video and does not write the factory theme?",
  },
  unavailableData: {
    type: "noul",
    instructions: "Do state.labels.explanation and state.labels.unknown indicate that values without live measurements are shown as unavailable rather than as real vehicle data?",
  },
  thaiScope: {
    type: "noul",
    instructions: "Does state.models contain exactly the Thai Zontes models 150X, 350D, 350E, 368D, 368E, 368G and 368K?",
  },
  previewScope: {
    type: "noul",
    instructions: "Does state.labels.styleHint say that the dashboard preview does not contain real vehicle data?",
  },
};

export function verifySuite(xml, shortName) {
  const expected = suites[shortName];
  if (!expected) throw new Error("ไม่รู้จักชุดทดสอบที่กำหนด");
  const suite = `dev.zanderp.opencfmoto.${shortName}`;
  const header = xml.match(/<testsuite\b([^>]+)>/)?.[1] ?? "";
  const count = (name) => Number(header.match(new RegExp(`\\b${name}="(\\d+)"`))?.[1]);
  const entries = [...xml.matchAll(/<testcase\b[^>]*>/g)].map(([tag]) => ({
    name: tag.match(/\bname="([^"]+)"/)?.[1],
    suite: tag.match(/\bclassname="([^"]+)"/)?.[1],
  }));
  if (!header.includes(`name="${suite}"`) || count("tests") !== entries.length ||
      ["failures", "errors", "skipped"].some((name) => count(name) !== 0) ||
      /<(?:failure|error|skipped)\b/.test(xml) ||
      entries.some((entry) => !entry.name || entry.suite !== suite) ||
      new Set(entries.map((entry) => entry.name)).size !== entries.length ||
      expected.some((name) => !entries.some((entry) => entry.name === name))) {
    throw new Error(`ผล JUnit ของ ${shortName} ไม่ครบหรือไม่ผ่าน`);
  }
  return expected;
}

export function reviewState(source, resources, results) {
  const block = source.match(/internal enum class ThaiZontesModel\b[\s\S]*?\{([\s\S]*?)\n\}/)?.[1] ?? "";
  const observed = [...block.matchAll(/\bZ[A-Z0-9]+\("Zontes ([0-9A-Z]+)"\)/g)].map((m) => m[1]);
  if (observed.length !== models.length || new Set(observed).size !== models.length ||
      models.some((name) => !observed.includes(name))) throw new Error("รายการรุ่นไทยไม่ตรงขอบเขตที่กำหนด");
  const readLabel = (key) => {
    const value = resources.match(new RegExp(`<string name="${key}">([^<]+)</string>`))?.[1];
    if (!value) throw new Error(`ไม่พบข้อความ ${key}`);
    return value;
  };
  return {
    models: observed,
    labels: { explanation: readLabel("ridescreen_live_explanation"), unknown: readLabel("ridescreen_live_unknown"), styleHint: readLabel("ridescreen_live_style_hint") },
    passedScenarios: Object.entries(suites).flatMap(([name]) => verifySuite(results[name] ?? "", name)),
    evidenceScope: "JUnit และข้อความในแอปเท่านั้น ไม่ใช่ผลทดสอบรถจริงหรือหลักฐานอนุญาตเขียนเฟิร์มแวร์",
  };
}

export function evaluateJudgments(answers) {
  const scores = {};
  for (const key of Object.keys(questions)) {
    const answer = answers?.[key];
    if (answer?.type !== "noul" || !Number.isFinite(answer.noul) || answer.noul < 0 || answer.noul > 1) {
      throw new Error("คำตอบ Jev ไม่ครบหรือรูปแบบไม่ถูกต้อง");
    }
    scores[key] = answer.noul;
  }
  return { scores, status: Object.values(scores).every((value) => value >= 0.85) ? "pass" : "review" };
}

export async function reviewWithJev(state, apiKey, fetcher = fetch) {
  if (!apiKey) throw new Error("ต้องตั้ง TYPESAFE_API_KEY ใน environment ก่อนตรวจด้วย Jev");
  let response;
  try {
    response = await fetcher("https://api.typesafe.ai/v1/systemone", {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "jev-latest", state, questions }),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new Error("ติดต่อ TypeSafe ไม่สำเร็จ");
  }
  if (!response.ok) throw new Error(`TypeSafe HTTP ${response.status}`);
  let result;
  try { result = await response.json(); } catch { throw new Error("TypeSafe ส่ง JSON ที่อ่านไม่ได้"); }
  return evaluateJudgments(result.answers);
}

async function main(args) {
  const [dir, mode, ...extra] = args;
  if (!dir || !["--local", "--live"].includes(mode) || extra.length) {
    throw new Error("วิธีใช้: node scripts/ridescreen-harness.mjs <โฟลเดอร์ผล JUnit> --local|--live");
  }
  const source = await readFile(new URL("../app/src/main/java/dev/zanderp/opencfmoto/ThaiZontesModels.kt", import.meta.url), "utf8");
  const resources = await readFile(new URL("../app/src/main/res/values/ridescreen_live.xml", import.meta.url), "utf8");
  const reports = Object.fromEntries(await Promise.all(Object.keys(suites).map(async (name) => [
    name, await readFile(join(dir, `TEST-dev.zanderp.opencfmoto.${name}.xml`), "utf8"),
  ])));
  const state = reviewState(source, resources, reports);
  console.log(JSON.stringify({ source: "junit", status: "pass", scenarios: state.passedScenarios.length }));
  if (mode === "--local") {
    console.log(JSON.stringify({ source: "jev", status: "not_run" }));
    return;
  }
  const result = await reviewWithJev(state, process.env.TYPESAFE_API_KEY);
  console.log(JSON.stringify({ source: "jev", ...result }));
  if (result.status !== "pass") process.exitCode = 2;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { await main(process.argv.slice(2)); }
  catch (error) {
    console.error(error.code ? "อ่านไฟล์หลักฐานสำหรับ harness ไม่สำเร็จ" : error.message);
    process.exitCode = 1;
  }
}
