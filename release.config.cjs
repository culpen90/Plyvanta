const conventionalCommits = {
  preset: "conventionalcommits",
  presetConfig: {},
};

function repositoryUrlFromEnvironment() {
  const repository = process.env.PLYVANTA_RELEASE_REPOSITORY;
  if (repository === undefined) {
    return undefined;
  }

  const components = repository.split("/");
  const validComponent = /^[A-Za-z0-9_.-]+$/;
  if (
    components.length !== 2
    || components.some((component) => (
      !validComponent.test(component)
      || component === "."
      || component === ".."
    ))
  ) {
    throw new Error(
      "PLYVANTA_RELEASE_REPOSITORY must be a canonical owner/repository name.",
    );
  }

  return `https://github.com/${repository}.git`;
}

const releaseConfig = {
  branches: ["main"],
  tagFormat: "v${version}",
  plugins: [
    ["@semantic-release/commit-analyzer", conventionalCommits],
    ["@semantic-release/release-notes-generator", conventionalCommits],
    [
      "@semantic-release/github",
      {
        assets: [
          {
            path: "app/build/outputs/stable/*.apk",
            label: "Production-signed Android APK",
          },
          {
            path: "app/build/outputs/stable/*-update.json",
            label: "Trusted update metadata",
          },
          {
            path: "app/build/outputs/stable/SHA256SUMS",
            label: "SHA-256 checksums",
          },
        ],
        releaseNameTemplate: "Plyvanta <%= nextRelease.version %>",
        releaseBodyTemplate:
          "Download **`Plyvanta-<%= nextRelease.version %>.apk`** to install "
          + "this release.\n\n<%= nextRelease.notes %>\n\n"
          + "### Release verification\n\n"
          + "The production-signed APK, trusted update metadata, and "
          + "`SHA256SUMS` were built and checked together. GitHub makes the "
          + "published release immutable and attaches a signed release "
          + "attestation.",
        draftRelease: false,
        successComment: false,
        failComment: false,
        releasedLabels: false,
        addReleases: false,
      },
    ],
    [
      "@semantic-release/exec",
      {
        prepareCmd:
          "./scripts/package-semantic-release.sh ${nextRelease.version}",
      },
    ],
  ],
};

const repositoryUrl = repositoryUrlFromEnvironment();
if (repositoryUrl !== undefined) {
  releaseConfig.repositoryUrl = repositoryUrl;
}

module.exports = releaseConfig;
