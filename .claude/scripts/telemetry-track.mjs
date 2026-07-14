/**
 * Lightweight telemetry tracker — counts skill / agent / command invocations
 * over the project's lifespan. No model involvement.
 *
 * Two invocation modes:
 *
 * 1. Hook mode (stdin JSON):
 *    Fires as a PostToolUse hook after Skill or Agent tool calls.
 *    stdin receives: { tool_name, tool_input }
 *
 * 2. CLI mode (direct argument):
 *    Commands self-report by calling:
 *      node .claude/scripts/telemetry-track.mjs --command <name>
 *
 * Reads/writes `docs/.telemetry.json` — a single JSON object keyed by
 * `skills.<name>`, `agents.<name>`, and `commands.<name>`, each with a
 * `count`, `firstSeen`, and `lastSeen` field.
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

// ── CLI mode: --command <name> ────────────────────────────────────────────────
const cmdIdx = process.argv.indexOf('--command');
if (cmdIdx !== -1 && cmdIdx + 1 < process.argv.length) {
  const commandName = process.argv[cmdIdx + 1];
  const data = load();
  increment(data, `commands.${commandName}`);
  save(data);
  process.exit(0);
}

// ── Hook mode: stdin JSON ────────────────────────────────────────────────────
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
