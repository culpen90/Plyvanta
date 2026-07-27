const conventionalCommits = {
  preset: "conventionalcommits",
  presetConfig: {},
};

module.exports = {
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
