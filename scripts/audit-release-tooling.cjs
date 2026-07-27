const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");

const repositoryRoot = path.resolve(__dirname, "..");
const releaseConfig = require(path.join(repositoryRoot, "release.config.cjs"));
const configuredPlugins = releaseConfig.plugins.map((plugin) =>
  Array.isArray(plugin) ? plugin[0] : plugin,
);

assert(
  !configuredPlugins.includes("@semantic-release/npm"),
  "@semantic-release/npm must remain disabled for this GitHub-only release.",
);

const audit = spawnSync("npm", ["audit", "--json"], {
  cwd: repositoryRoot,
  encoding: "utf8",
  maxBuffer: 16 * 1024 * 1024,
});
if (audit.error) {
  throw audit.error;
}

let report;
try {
  report = JSON.parse(audit.stdout);
} catch (error) {
  process.stderr.write(audit.stderr);
  throw new Error(`npm audit did not return JSON: ${error.message}`);
}

if (audit.status === 0) {
  assert.equal(report.metadata.vulnerabilities.total, 0);
  console.log("Release tooling audit passed with no findings.");
  process.exit(0);
}
assert.equal(audit.status, 1, audit.stderr || "npm audit failed unexpectedly.");
assert.equal(report.metadata.vulnerabilities.critical, 0);

// semantic-release installs @semantic-release/npm as an unused default plugin,
// even when an explicit GitHub-only plugin list replaces the defaults. npm
// bundles the two vulnerable packages below, so package overrides cannot patch
// them. Permit only these exact denial-of-service advisories in that unreachable
// subtree; any new advisory, path, or severity still fails CI.
const allowedAdvisories = new Map([
  [
    "1124287",
    {
      name: "tar",
      severity: "moderate",
      url: "https://github.com/advisories/GHSA-r292-9mhp-454m",
      node:
        "node_modules/@semantic-release/npm/node_modules/npm/"
        + "node_modules/tar",
      version: "7.5.19",
    },
  ],
  [
    "1124334",
    {
      name: "brace-expansion",
      severity: "high",
      url: "https://github.com/advisories/GHSA-mh99-v99m-4gvg",
      node:
        "node_modules/@semantic-release/npm/node_modules/npm/"
        + "node_modules/brace-expansion",
      version: "5.0.7",
    },
  ],
]);

const expectedVulnerabilities = new Map([
  [
    "@semantic-release/commit-analyzer",
    ["moderate", "node_modules/@semantic-release/commit-analyzer"],
  ],
  ["@semantic-release/exec", ["moderate", "node_modules/@semantic-release/exec"]],
  [
    "@semantic-release/github",
    ["moderate", "node_modules/@semantic-release/github"],
  ],
  ["@semantic-release/npm", ["moderate", "node_modules/@semantic-release/npm"]],
  [
    "@semantic-release/release-notes-generator",
    ["moderate", "node_modules/@semantic-release/release-notes-generator"],
  ],
  [
    "brace-expansion",
    [
      "high",
      "node_modules/@semantic-release/npm/node_modules/npm/"
        + "node_modules/brace-expansion",
    ],
  ],
  [
    "npm",
    ["moderate", "node_modules/@semantic-release/npm/node_modules/npm"],
  ],
  ["semantic-release", ["moderate", "node_modules/semantic-release"]],
  [
    "tar",
    [
      "moderate",
      "node_modules/@semantic-release/npm/node_modules/npm/node_modules/tar",
    ],
  ],
]);

for (const [name, vulnerability] of Object.entries(report.vulnerabilities)) {
  const expected = expectedVulnerabilities.get(name);
  assert(expected, `Unapproved vulnerable package: ${name}`);
  assert.equal(vulnerability.severity, expected[0], name);
  assert.deepEqual(vulnerability.nodes, [expected[1]], name);
}

const concreteAdvisories = new Map();
for (const vulnerability of Object.values(report.vulnerabilities)) {
  for (const cause of vulnerability.via) {
    if (typeof cause === "string") {
      continue;
    }
    concreteAdvisories.set(String(cause.source), {
      name: cause.name,
      severity: cause.severity,
      url: cause.url,
      nodes: vulnerability.nodes,
    });
  }
}

assert(concreteAdvisories.size > 0, "npm audit reported no concrete advisory.");
for (const [source, actual] of concreteAdvisories) {
  const allowed = allowedAdvisories.get(source);
  assert(allowed, `Unapproved npm advisory: ${source} (${actual.name})`);
  assert.equal(actual.name, allowed.name, source);
  assert.equal(actual.severity, allowed.severity, source);
  assert.equal(actual.url, allowed.url, source);
  assert.deepEqual(actual.nodes, [allowed.node], source);
}

for (const vulnerabilityName of Object.keys(report.vulnerabilities)) {
  const visited = new Set();
  const queue = [vulnerabilityName];
  const resolvedSources = new Set();
  while (queue.length > 0) {
    const name = queue.shift();
    if (visited.has(name)) {
      continue;
    }
    visited.add(name);
    const vulnerability = report.vulnerabilities[name];
    assert(vulnerability, `Unknown transitive audit cause: ${name}`);
    for (const cause of vulnerability.via) {
      if (typeof cause === "string") {
        queue.push(cause);
      } else {
        resolvedSources.add(String(cause.source));
      }
    }
  }
  assert(resolvedSources.size > 0, `No concrete cause for ${vulnerabilityName}`);
  for (const source of resolvedSources) {
    assert(
      allowedAdvisories.has(source),
      `Unapproved advisory ${source} affects ${vulnerabilityName}`,
    );
  }
}

const lockfile = require(path.join(repositoryRoot, "package-lock.json"));
for (const allowed of allowedAdvisories.values()) {
  if (!report.vulnerabilities[allowed.name]) {
    continue;
  }
  const lockedPackage = lockfile.packages[allowed.node];
  assert(lockedPackage, `Missing audited lock entry: ${allowed.node}`);
  assert.equal(lockedPackage.version, allowed.version, allowed.name);
  assert.equal(lockedPackage.inBundle, true, allowed.name);
}

console.warn(
  "Release tooling audit accepted two known DoS advisories in the unused "
    + "bundled @semantic-release/npm subtree.",
);
