# Secrets

Put files here that must not go into git, e.g. the Google service account's key:

    secrets/google-sa.json

The folder is mounted read-only as `/app/secrets` in the Pluggis container. Everything except this file is ignored by git.
