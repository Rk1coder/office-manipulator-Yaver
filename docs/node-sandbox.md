# Document generation sandbox

Model-authored JavaScript always runs inside Linux bubblewrap (`bwrap`). There is no direct-Node fallback and no setting to disable isolation. If namespaces, the runtime, fonts, or sandbox tools are unavailable, generation fails with an actionable error before an output document is published.

## Supported local setup

Use a Linux host that permits unprivileged user, PID, mount and network namespaces, Node **24.19.0**, bubblewrap, `prlimit` (util-linux), and DejaVu Sans. Debian/Ubuntu packages for the non-Node dependencies are `bubblewrap util-linux fonts-dejavu-core`. Run the application from its repository directory so it finds the local `node_modules` directory:

```sh
npm ci --ignore-scripts
mvn -DskipTests package
java -jar target/office-manipulator-1.0.0-SNAPSHOT.jar
```

Use the project's usual backend/database configuration. The environment is available to the backend but is **not** inherited by generated scripts. No additional Node environment variables are required for Turkish PDF output on Debian/Ubuntu or the supplied Alpine image: the runner locates DejaVu Sans in the standard font directories. For another layout, configure trusted backend variables `TURKISH_FONT_REGULAR` and `TURKISH_FONT_BOLD` with the actual font paths.

If binaries are not on the backend's `PATH`, use absolute JVM properties **before** `-jar`:

```sh
java -Dnode.binary=/absolute/path/to/node \
     -Dnode.sandbox.binary=/usr/bin/bwrap \
     -Dnode.prlimit.binary=/usr/bin/prlimit \
     -Dnode.modules.path=/absolute/path/to/office-manipulator/node_modules \
     -jar target/office-manipulator-1.0.0-SNAPSHOT.jar
```

`node.modules.path` is one trusted directory containing the lockfile-installed packages, not a colon-separated search path. A missing executable or directory is an error; it never opts out of sandboxing.

Run the real integration checks on the intended deployment host:

```sh
AUDIT_PARENT_SECRET=fake-audit-value mvn -Dtest=GeneratorTest,NodeSandboxTest test
```

The timeout test takes about 60 seconds. It must report a timeout, not an environment/setup failure. These tests require namespaces and a temporary local listener for the network isolation test. An outer CI sandbox that prohibits either must run the tests in a dedicated compatible Linux runner; bypassing these checks does not establish production compatibility.

## Isolation and resource limits

Each invocation gets its own private staging directory. Only the generated script and validated `IMG_<number>` assets belonging to that job are staged. Generated scripts see:

- Read-only `/job` inputs, Node/modules/fonts under `/runtime`, and system runtime library directories. Backend application files, `/home`, `/etc`, other jobs and backend environment variables are absent.
- A fresh PID namespace and a separate network namespace without the host's interfaces or listeners.
- One writable output file whose original filename/extension is preserved, and a 64 MiB temporary filesystem at `/work`. The host job directory is never mounted.
- An allowlisted environment: output path, staged image paths, module path, font paths, locale and sandbox-local home/temp paths. `NODE_OPTIONS`, provider keys and arbitrary caller environment entries are rejected or absent.

Bubblewrap drops capabilities and creates a new session. Node's permission model additionally denies child processes, worker threads, native addons and access outside the runtime/job/work/output paths. Native modules or libraries that require spawning programs are intentionally unsupported.

`prlimit` applies a 2 GiB virtual-address-space ceiling, 60 CPU seconds, a 64 MiB file-size ceiling, 128 file descriptors and no core dumps. Node's heap ceiling is 256 MiB. The Java runner applies a separate 60-second wall-clock deadline while concurrently draining both output streams. At most 64 KiB per stream is retained; excess diagnostics are discarded and marked truncated. Timeout, interruption and errors terminate the process tree; destroying the PID namespace also kills reparented descendants. Only a nonempty regular output file is copied back after successful execution.

## Docker compatibility and current deployment limit

The Dockerfile installs the sandbox tools, pins Node 24.19.0, uses the same `package-lock.json` through `npm ci --omit=dev --ignore-scripts`, and tests real Turkish PDF generation with DejaVu Sans during the runtime build:

```sh
docker build --target runtime -t office-sandbox-runtime .
```

This confirms runtime/dependency installation; it does **not** prove namespace availability when a container is started.

The current host's default Docker security policies block Bubblewrap: seccomp rejects namespace creation, and AppArmor can reject mount setup (`Failed to make / slave: Permission denied`). **Document generation through the sandbox is unavailable in this container configuration.** Use the native host setup described in [onlyoffice-setup.md](onlyoffice-setup.md), or validate the target container host's security policies before enabling generation. The runner fails closed when isolation cannot be established.

Deploy on the verified compatible Linux host, or provision a dedicated container host with reviewed policies that permit unprivileged bubblewrap setup. Required operations include namespace creation, bind/tmpfs/proc/dev mounts, mount propagation, pivot-root and unmount operations within the newly created user/mount namespace. Policy review must cover both seccomp and the host LSM (AppArmor/SELinux); allowing a syscall alone is insufficient. Run `NodeSandboxTest` in that final environment before enabling generation. No unverified custom profile is shipped as a working deployment configuration. Do not solve this with `--privileged`, `CAP_SYS_ADMIN`, `seccomp=unconfined`, an unconfined AppArmor profile, or access to the Docker socket from generated code.

References: [Docker seccomp documentation](https://docs.docker.com/engine/security/seccomp/), [Docker's default seccomp profile](https://github.com/moby/profiles/blob/main/seccomp/default.json).

## Dependency maintenance

Direct dependencies use exact versions in `package.json` and `package-lock.json`. Overrides pin `image-size` 2.0.3 and ExcelJS's `uuid` 11.1.1. Run `npm audit` when reviewing dependency security, and rerun the generator and sandbox integration tests after dependency updates. Some upstream transitive packages emit deprecation notices.
