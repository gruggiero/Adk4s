# CI templates for registry-check

`scanner/registry-check.sh` is dependency-free (bash + git grep), so it runs
on any CI runner — including runners that cannot reach the build's artifact
repositories. Pick the template matching your host, copy it to the expected
location, and register the pipeline if your host requires it (Azure DevOps
does; GitLab picks up `.gitlab-ci.yml` automatically — check for an existing
server-side pipeline configuration before committing one).

| Host | Template | Copy to |
|---|---|---|
| Azure DevOps | `azure-pipelines.yml` | repo root (then create a pipeline pointing at it) |
| GitLab | `gitlab-ci.yml` | repo root as `.gitlab-ci.yml`, or include as a job in the existing one |
| GitHub | `github-actions.yml` | `.github/workflows/registry-check.yml` |
