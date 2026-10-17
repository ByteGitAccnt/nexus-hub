# Implement the Gateway route rewrite so requests to /nexushub/** are forwarded to the Management App at http://localhost:8090 with only the /nexushub prefix stripped. Preserve existing routes and configuration behavior; inspect and test this change. Do not change JWT security enablement unless the approved task plan explicitly requires it.

Plan: `6ad358dcda84b55fbd164fda`
Repositories: ByteGitAccnt/nexus-hub

## Tasks

- [x] Stripping the NexusHub path prefix

---

Written by code0 when the plan was approved. The plan itself lives outside this
repository — see `~/.code0/plans/` — because a plan can span repositories and is
not a source file. This note is the thread back to it.
