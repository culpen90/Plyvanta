const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "..");
const releaseWorkflow = readFileSync(
  path.join(repositoryRoot, ".github", "workflows", "release.yml"),
  "utf8",
);
const releaseConfig = require(path.join(repositoryRoot, "release.config.cjs"));
const pluginEntries = new Map(
  releaseConfig.plugins.map((plugin) => (
    Array.isArray(plugin) ? [plugin[0], plugin[1]] : [plugin, {}]
  )),
);
const analyzerOptions = pluginEntries.get("@semantic-release/commit-analyzer");
const versionCodeScript = path.join(
  repositoryRoot,
  "scripts",
  "semantic-version-code.sh",
);

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

  const notesOptions = pluginEntries.get(
    "@semantic-release/release-notes-generator",
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
