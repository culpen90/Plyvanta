const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "..");
const releaseConfigPath = path.join(
  repositoryRoot,
  "release.config.cjs",
);
const releaseWorkflow = readFileSync(
  path.join(repositoryRoot, ".github", "workflows", "release.yml"),
  "utf8",
);
const releaseConfig = loadReleaseConfig();
const pluginEntries = new Map(
  releaseConfig.plugins.map((plugin) => (
    Array.isArray(plugin) ? [plugin[0], plugin[1]] : [plugin, {}]
  )),
);
const analyzerOptions = pluginEntries.get("@semantic-release/commit-analyzer");
const notesOptions = pluginEntries.get(
  "@semantic-release/release-notes-generator",
);
const versionCodeScript = path.join(
  repositoryRoot,
  "scripts",
  "semantic-version-code.sh",
);

function loadReleaseConfig(repositoryOverride) {
  const environmentName = "PLYVANTA_RELEASE_REPOSITORY";
  const previousValue = process.env[environmentName];
  const previouslyPresent = Object.hasOwn(process.env, environmentName);
  if (repositoryOverride === undefined) {
    delete process.env[environmentName];
  } else {
    process.env[environmentName] = repositoryOverride;
  }
  delete require.cache[require.resolve(releaseConfigPath)];

  try {
    return require(releaseConfigPath);
  } finally {
    delete require.cache[require.resolve(releaseConfigPath)];
    if (previouslyPresent) {
      process.env[environmentName] = previousValue;
    } else {
      delete process.env[environmentName];
    }
  }
}

async function releaseType(message) {
  const { analyzeCommits } = await import("@semantic-release/commit-analyzer");
  return analyzeCommits(
    analyzerOptions,
    {
      commits: [{ hash: "0123456789abcdef", message }],
      cwd: repositoryRoot,
      logger: {
        log() {},
      },
    },
  );
}

async function releaseNotes(messages) {
  const { generateNotes } = await import(
    "@semantic-release/release-notes-generator"
  );
  const commits = messages.map((message, index) => ({
    hash: `${index + 1}`.padStart(40, "0"),
    message,
  }));

  return generateNotes(
    notesOptions,
    {
      commits,
      cwd: repositoryRoot,
      lastRelease: {
        gitHead: "0".repeat(40),
        gitTag: "v1.0.0",
      },
      nextRelease: {
        gitHead: commits.at(-1).hash,
        gitTag: "v1.1.0",
        version: "1.1.0",
      },
      options: {
        repositoryUrl: "https://github.com/Plyvanta/Plyvanta.git",
      },
    },
  );
}

test("release repository override uses the canonical HTTPS Git URL", () => {
  const config = loadReleaseConfig("Renamed-Org/Plyvanta.App_2");

  assert.equal(
    config.repositoryUrl,
    "https://github.com/Renamed-Org/Plyvanta.App_2.git",
  );
});

test("absent repository override preserves the package.json fallback", () => {
  assert.equal(Object.hasOwn(releaseConfig, "repositoryUrl"), false);
});

test("invalid release repository overrides are rejected", () => {
  for (const repository of [
    "",
    "Plyvanta",
    "Plyvanta/Plyvanta/extra",
    "Plyvanta/..",
    "Plyvanta/Ply vanta",
    "Plyvanta/Plyvanta?ref=main",
    "https://github.com/Plyvanta/Plyvanta",
    "Plyvanta/Plyvanta\nINJECTED=value",
  ]) {
    assert.throws(
      () => loadReleaseConfig(repository),
      /must be a canonical owner\/repository name/,
      repository,
    );
  }
});

test("release preflight propagates the resolved trusted repository", () => {
  const temporaryDirectory = mkdtempSync(
    path.join(os.tmpdir(), "plyvanta-release-preflight-"),
  );
  try {
    const fakeBin = path.join(temporaryDirectory, "bin");
    const fakeGitHub = path.join(fakeBin, "gh");
    const githubEnvironment = path.join(temporaryDirectory, "github-env");
    mkdirSync(fakeBin);
    writeFileSync(
      fakeGitHub,
      `#!/usr/bin/env bash
set -euo pipefail
case "\${2:-}" in
  repositories/1313062669)
    printf '%s\\n' '{"id":1313062669,"full_name":"Renamed-Org/Plyvanta"}'
    ;;
  repos/Renamed-Org/Plyvanta/releases/latest)
    printf 'false\\tfalse\\ttrue\\n'
    ;;
  *)
    exit 1
    ;;
esac
`,
    );
    chmodSync(fakeGitHub, 0o755);

    execFileSync(
      path.join(repositoryRoot, "scripts", "verify-release-preconditions.sh"),
      [],
      {
        cwd: repositoryRoot,
        env: {
          ...process.env,
          PATH: `${fakeBin}:${process.env.PATH}`,
          GITHUB_ENV: githubEnvironment,
          GITHUB_REF: "refs/heads/main",
          GITHUB_REPOSITORY: "Renamed-Org/Plyvanta",
          GH_TOKEN: "test-token",
          PLYVANTA_RELEASE_STORE_BASE64: "test-store",
          PLYVANTA_RELEASE_STORE_PASSWORD: "test-password",
          PLYVANTA_RELEASE_KEY_ALIAS: "test-alias",
          PLYVANTA_RELEASE_KEY_PASSWORD: "test-password",
        },
        stdio: "pipe",
      },
    );

    assert.equal(
      readFileSync(githubEnvironment, "utf8"),
      "PLYVANTA_RELEASE_REPOSITORY=Renamed-Org/Plyvanta\n",
    );
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test("release configuration keeps the guarded main-branch pipeline", () => {
  assert.deepEqual(releaseConfig.branches, ["main"]);
  assert.equal(releaseConfig.tagFormat, "v${version}");
  assert.deepEqual(
    releaseConfig.plugins.map((plugin) => (
      Array.isArray(plugin) ? plugin[0] : plugin
    )),
    [
      "@semantic-release/commit-analyzer",
      "@semantic-release/release-notes-generator",
      "@semantic-release/github",
      "@semantic-release/exec",
    ],
  );

  assert.deepEqual(notesOptions, analyzerOptions);

  const githubOptions = pluginEntries.get("@semantic-release/github");
  assert.equal(githubOptions.draftRelease, false);
  assert.deepEqual(
    githubOptions.assets.map(({ path: assetPath }) => assetPath),
    [
      "app/build/outputs/stable/*.apk",
      "app/build/outputs/stable/*-update.json",
      "app/build/outputs/stable/SHA256SUMS",
    ],
  );
  assert.equal(githubOptions.successComment, false);
  assert.equal(githubOptions.failComment, false);
  assert.equal(githubOptions.releasedLabels, false);
  assert.equal(githubOptions.addReleases, false);
  assert.match(
    githubOptions.releaseBodyTemplate,
    /<%= nextRelease\.notes %>/,
  );
  assert.ok(
    githubOptions.releaseBodyTemplate.indexOf("<%= nextRelease.notes %>")
      < githubOptions.releaseBodyTemplate.indexOf("### Release verification"),
  );

  assert.equal(
    pluginEntries.get("@semantic-release/exec").prepareCmd,
    "./scripts/package-semantic-release.sh ${nextRelease.version}",
  );
});

test("Semantic Release receives the Actions token with Git transport credentials", () => {
  const publishStep = releaseWorkflow.match(
    /      - name: Build, sign, and publish the semantic release\n[\s\S]*?(?=\n      - name: Verify any release for this commit)/,
  );
  assert(publishStep, "Could not find the Semantic Release workflow step.");
  assert.match(
    publishStep[0],
    /\n          GITHUB_TOKEN: \$\{\{ secrets\.GITHUB_TOKEN \}\}/,
  );
  assert.doesNotMatch(publishStep[0], /\n          GH_TOKEN:/);
});

test("Conventional Commits map to the intended release levels", async () => {
  assert.equal(await releaseType("fix: repair playback"), "patch");
  assert.equal(await releaseType("perf: reduce startup work"), "patch");
  assert.equal(
    await releaseType(
      "revert: feat: add a queue\n\nThis reverts commit 0123456789abcdef.",
    ),
    "patch",
  );
  assert.equal(await releaseType("feat: add a queue"), "minor");
  assert.equal(await releaseType("feat!: replace the playback contract"), "major");
  assert.equal(
    await releaseType(
      "chore: reorganize internals\n\nBREAKING CHANGE: remove the old API",
    ),
    "major",
  );
});

test("non-product Conventional Commits do not publish releases", async () => {
  for (const message of [
    "build: update build tooling",
    "chore: maintain dependencies",
    "ci: configure automation",
    "docs: clarify installation",
    "refactor: reorganize helpers",
    "style: format sources",
    "test: cover update metadata",
  ]) {
    assert.equal(await releaseType(message), null, message);
  }
});

test("generated release notes explain each user-visible update", async () => {
  const notes = await releaseNotes([
    "feat(settings): add manual update check (#6)",
    "fix(playback): recover a stalled stream",
    "docs: clarify installation",
  ]);

  assert.match(notes, /### Features/);
  assert.match(notes, /\*\*settings:\*\* add manual update check/);
  assert.match(notes, /### Bug Fixes/);
  assert.match(notes, /\*\*playback:\*\* recover a stalled stream/);
  assert.doesNotMatch(notes, /clarify installation/);
});

test("semantic versions map to deterministic Android version codes", () => {
  const cases = new Map([
    ["1.0.0", "1000000"],
    ["1.0.1", "1000001"],
    ["1.2.3", "1002003"],
    ["2.0.0", "2000000"],
    ["2100.0.0", "2100000000"],
  ]);
  for (const [version, expected] of cases) {
    const actual = execFileSync(versionCodeScript, [version], {
      encoding: "utf8",
    }).trim();
    assert.equal(actual, expected, version);
  }
});

test("invalid or unrepresentable semantic versions are rejected", () => {
  for (const version of [
    "0.1.0",
    "01.0.0",
    "1.0",
    "1.0.0-alpha.1",
    "1.1000.0",
    "1.0.1000",
    "2100.0.1",
  ]) {
    assert.throws(
      () =>
        execFileSync(versionCodeScript, [version], {
          stdio: "pipe",
        }),
      version,
    );
  }
});
