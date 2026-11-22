import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

// Usage: node scripts/tft-skill-harness.mjs <TftStudioSettingsTest XML> [--live]
const suite = "dev.zanderp.opencfmoto.TftStudioSettingsTest";
const scenarios = {
  browsePreservesSavedSetup: "Browsing a preset leaves the saved custom base, colors, and photo intact.",
  factoryRoundTripAfterReopen: "Factory preview survives reopening and My layout recovers the original setup.",
  changingBaseIsExplicit: "The editor can deliberately select a different base for the custom setup.",
  resetClearsAppearanceOnly: "Reset clears colors and photo while keeping the base, day mode, and selected readout.",
  legacyPreferencesStillLoad: "Existing settings and legacy photo strength still load.",
};

const questions = {
  factoryScope: {
    type: "noul",
    instructions: "Taken together, do the factory restore button, guide, preview confirmation, and file copy status distinguish stopping RideLink projection and restoring the phone preview from writing a factory file to the motorcycle?",
  },
  restoreSavedSetup: {
    type: "noul",
    instructions: "Can a user distinguish the label for viewing their saved layout from the label for resetting its custom colors and photo?",
  },
  resetDisclosure: {
    type: "noul",
    instructions: "Does the reset confirmation in state.labels explicitly disclose both restoring the base theme colors and removing the background photo?",
  },
};

export function verifyJUnit(xml) {
  const summary = xml.match(/<testsuite\b([^>]+)>/)?.[1] ?? "";
  const names = [...xml.matchAll(/<testcase\b[^>]*>/g)].map(([tag]) => ({
    name: tag.match(/\bname="([^"]+)"/)?.[1],
    suite: tag.match(/\bclassname="([^"]+)"/)?.[1],
  }));
  const count = (attribute) => Number(summary.match(new RegExp("\\b" + attribute + '="(\\d+)"'))?.[1]);
  const valid = summary.includes(`name="${suite}"`) &&
    count("tests") === names.length &&
    ["failures", "errors", "skipped"].every((field) => count(field) === 0) &&
    !/<(?:failure|error|skipped)\b/.test(xml) &&
    names.every((test) => test.name && test.suite === suite) &&
    new Set(names.map((test) => test.name)).size === names.length &&
    Object.keys(scenarios).every((name) => names.some((test) => test.name === name));
  if (!valid) throw new Error("ผล JUnit ต้องมีกรณีของ TFT Studio ครบและผ่านทั้งหมด");
  return Object.values(scenarios);
}

export function evaluateJudgments(answers) {
  const scores = {};
  for (const key of Object.keys(questions)) {
    const result = answers?.[key];
    const score = result?.noul;
    if (result?.type !== "noul" || !Number.isFinite(score) || score < 0 || score > 1) {
      throw new Error("คำตอบ Jev ไม่ครบหรือรูปแบบไม่ถูกต้อง");
    }
    scores[key] = score;
  }
  return { scores, status: Object.values(scores).every((score) => score >= 0.85) ? "pass" : "review" };
}

async function liveReview(evidence) {
  const apiKey = process.env.TYPESAFE_API_KEY;
  if (!apiKey) throw new Error("ต้องตั้ง TYPESAFE_API_KEY ใน environment เพื่อรัน Jev แบบ live");

  // Send only allowlisted UI labels and synthetic scenario descriptions.
  const resources = await readFile(new URL("../app/src/main/res/values-en/tft_studio.xml", import.meta.url), "utf8");
  const labels = Object.fromEntries([
    "tft_back_to_factory", "tft_factory_vehicle_guide", "tft_factory_preview_restored",
    "tft_backup_missing", "tft_backup_exported",
    "tft_use_custom", "tft_reset_custom", "tft_reset_custom_message", "tft_reset_custom_confirm",
  ].map((key) => {
    const label = resources.match(new RegExp(`<string name="${key}">([^<]+)</string>`))?.[1];
    if (!label) throw new Error("ข้อความ TFT Studio สำหรับการตรวจ Jev ไม่ครบ");
    return [key, label];
  }));

  let response;
  try {
    response = await fetch("https://api.typesafe.ai/v1/systemone", {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "jev-latest", state: { labels, evidence }, questions }),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new Error("ติดต่อ TypeSafe ไม่สำเร็จ");
  }
  if (!response.ok) throw new Error(`TypeSafe HTTP ${response.status}`);
  let result;
  try {
    result = await response.json();
  } catch {
    throw new Error("TypeSafe ส่งผลลัพธ์ JSON ที่อ่านไม่ได้");
  }
  const verdict = evaluateJudgments(result.answers);
  console.log(JSON.stringify({ source: "jev", ...verdict }));
  if (verdict.status === "review") process.exitCode = 2;
}

async function main(args) {
  const live = args.includes("--live");
  const [report, ...extra] = args.filter((arg) => arg !== "--live");
  if (!report || report.startsWith("-") || extra.length) {
    throw new Error("วิธีใช้ node scripts/tft-skill-harness.mjs <ผล JUnit ของ TftStudioSettingsTest> [--live]");
  }
  const evidence = verifyJUnit(await readFile(report, "utf8"));
  console.log(JSON.stringify({ source: "junit", status: "pass", scenarios: evidence.length }));
  if (live) await liveReview(evidence);
  else console.log(JSON.stringify({ source: "jev", status: "skipped" }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    console.error(error.code ? "อ่านไฟล์หลักฐานสำหรับ harness ไม่สำเร็จ" : error.message);
    process.exitCode = 1;
  }
}
