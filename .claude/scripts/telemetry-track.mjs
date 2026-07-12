/**
 * Lightweight telemetry tracker — counts skill / agent invocations over the
 * project's lifespan. No model involvement; runs as a PostToolUse hook that
 * fires after every Skill or Agent tool call.
 *
 * Reads/writes `docs/.telemetry.json` — a single JSON object keyed by
 * `skills.<name>` and `agents.<name>`, each with a `count` field.
 *
 * Usage (configured via settings.json PostToolUse hook):
 *   node .claude/scripts/telemetry-track.mjs
 *
 * stdin receives the hook JSON: { tool_name, tool_input }
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const REPO_ROOT = process.env.CLAUDE_PROJECT_DIR ?? process.cwd();
const TELEMETRY_PATH = join(REPO_ROOT, 'docs', '.telemetry.json');

function load() {
  try {
    const raw = readFileSync(TELEMETRY_PATH, 'utf-8');
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

function save(data) {
  writeFileSync(TELEMETRY_PATH, JSON.stringify(data, null, 2) + '\n', 'utf-8');
}

function increment(data, key) {
  if (!data[key]) {
    data[key] = { count: 0, firstSeen: new Date().toISOString().slice(0, 10) };
  }
  data[key].count += 1;
  data[key].lastSeen = new Date().toISOString().slice(0, 10);
}

// ── main ────────────────────────────────────────────────────────────────────
let input = '';
process.stdin.setEncoding('utf-8');
process.stdin.on('data', (chunk) => { input += chunk; });
process.stdin.on('end', () => {
  try {
    const payload = JSON.parse(input);
    const toolName = payload.tool_name;

    if (toolName !== 'Skill' && toolName !== 'Agent') {
      process.exit(0);
    }

    const data = load();

    if (toolName === 'Skill') {
      const skillName = payload.tool_input?.skill ?? 'unknown';
      increment(data, `skills.${skillName}`);
    }

    if (toolName === 'Agent') {
      const agentType = payload.tool_input?.subagent_type ?? 'general-purpose';
      increment(data, `agents.${agentType}`);
    }

    save(data);
  } catch {
    // Never block the user on a telemetry failure — silent exit.
  }
  process.exit(0);
});
