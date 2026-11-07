"""W0 disposable server acceptance. Python stdlib + Docker + an already built Java 25 reactor.

Never connects to a supplied DB: endpoint/schema are fixed and a new labelled tmpfs
MariaDB is created per run. Existing output directories are refused. Results remain
outside the checkout; the owned container is stopped in finally, including failures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

PHASES = ("exercise", "restore")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    args = parser.parse_args()
    repo, output, java = args.repo.resolve(), args.output.resolve(), args.java.resolve()
    if output.exists() or output.is_relative_to(repo):
        raise RuntimeError("Use a new output directory outside the repository")
    if not java.is_file():
        raise RuntimeError("Java executable does not exist")
    gs = repo / "game-server"
    compiled = gs / "target/test-classes/com/aionemu/gameserver/services/ai/memory/CompanionW0RuntimeProbe.class"
    source = gs / "test/com/aionemu/gameserver/services/ai/memory/CompanionW0RuntimeProbe.java"
    java_sources = sorted(list((gs / "src").rglob("*.java")) + list((gs / "test").rglob("*.java")))
    package_file = gs / "target/game-server.zip"
    if not compiled.is_file() or compiled.stat().st_mtime < source.stat().st_mtime or not package_file.is_file() or package_file.stat().st_mtime < max(p.stat().st_mtime for p in java_sources):
        raise RuntimeError("Build the reactor with tests before running W0")
    subprocess.run(["docker", "image", "inspect", "mariadb:11.4"], check=True, capture_output=True)
    git = ["git", "-c", "safe.directory=" + str(repo).replace("\\", "/"), "-C", str(repo)]
    def git_bytes(*parts):
        return subprocess.check_output(git + list(parts), stderr=subprocess.DEVNULL)
    output.mkdir(parents=True)
    runtime = output / "runtime"
    (runtime / "libs").mkdir(parents=True)
    for name in git_bytes("ls-files", "game-server/config").decode().splitlines():
        src = (repo / name).resolve()
        if not src.is_relative_to(gs / "config"):
            raise RuntimeError("Config path escaped repository")
        dest = runtime / "config" / src.relative_to(gs / "config")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
    with zipfile.ZipFile(gs / "target/game-server.zip") as package:
        for name in package.namelist():
            if name.startswith("game-server/libs/") and name.endswith(".jar"):
                (runtime / "libs" / Path(name).name).write_bytes(package.read(name))
    data = gs / "data"
    if os.name == "nt":
        # LiteralPath-safe quoting; no deletion/move or cross-shell path enumeration.
        q = lambda value: "'" + str(value).replace("'", "''") + "'"
        script = "New-Item -ItemType Junction -Path " + q(runtime / "data") + " -Target " + q(data) + " | Out-Null"
        subprocess.run(["powershell", "-NoProfile", "-Command", script], check=True, capture_output=True)
    else:
        (runtime / "data").symlink_to(data, target_is_directory=True)
    name = "aion-ai-w0-" + uuid.uuid4().hex[:12]
    root_password, password = secrets.token_hex(24), secrets.token_hex(24)
    env = dict(os.environ, MARIADB_ROOT_PASSWORD=root_password)
    config = runtime / "config/mygs.properties"
    config.write_text("\n".join((
        "database.user=ai_w0", "database.password=" + password,
        "database.url=jdbc:mysql://127.0.0.1:53418/ai_w0_runtime?serverTimezone=Europe/Moscow&characterEncoding=UTF-8",
        "gameserver.network.client.connect_address=127.0.0.1:17788",
        "gameserver.network.client.socket_address=127.0.0.1:17788",
        "gameserver.network.login.address=127.0.0.1:19028", "gameserver.network.login.gsid=1",
        "gameserver.network.login.password=w0-probe", "gameserver.chatserver.enable=false",
        "ai.world_memory.realm=w0_probe", "gameserver.timezone=Europe/Moscow", "gameserver.administration.login.execute_commands=",
        "gameserver.shutdown.delay=0", "gameserver.shutdown.restart_schedule=", "")), encoding="utf-8")
    sources_hash = hashlib.sha256()
    for path in java_sources + [Path(__file__).resolve()]:
        sources_hash.update(str(path.relative_to(repo)).replace("\\", "/").encode())
        sources_hash.update(b"\0" + path.read_bytes() + b"\0")
    report = {"head": git_bytes("rev-parse", "HEAD").decode().strip(),
              "diff_sha256": hashlib.sha256(git_bytes("diff", "HEAD", "--binary")).hexdigest(),
              "java_sources_and_runner_sha256": sources_hash.hexdigest(),
              "server_package_sha256": hashlib.sha256(package_file.read_bytes()).hexdigest(),
              "container": name, "database": "ai_w0_runtime", "phases": [], "status": "RUNNING",
              "limits": "Explicit immutable witness-boundary fixture; no real client, connection or gameplay acceptance."}
    created = False
    def sql(query):
        return subprocess.run(["docker", "exec", "-i", name, "sh", "-c",
                               'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" exec mariadb -uroot -N -B'],
                              input=query, text=True, encoding="utf-8", capture_output=True, timeout=90)
    try:
        subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "--label", "codex.ai.acceptance=W0",
                        "-p", "127.0.0.1:53418:3306", "--tmpfs", "/var/lib/mysql:rw,size=1073741824",
                        "-e", "MARIADB_ROOT_PASSWORD", "mariadb:11.4"], env=env, check=True, capture_output=True, timeout=60)
        created = True
        for _ in range(60):
            if sql("SELECT 1;").returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable MariaDB did not become ready")
        schema = "CREATE DATABASE ai_w0_runtime CHARACTER SET utf8mb4; USE ai_w0_runtime;\n"
        schema += (gs / "sql/aion_gs.sql").read_text(encoding="utf-8")
        if sql(schema).returncode:
            raise RuntimeError("Fresh schema import failed")
        if sql(f"CREATE USER 'ai_w0'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON ai_w0_runtime.* TO 'ai_w0'@'%';").returncode:
            raise RuntimeError("Test database user setup failed")
        classpath = os.pathsep.join(map(str, (gs / "target/test-classes", gs / "target/classes", runtime / "libs/*")))
        command = [str(java), "-Xms512m", "-Xmx3g", "-Dfile.encoding=UTF-8", "-Dlogback.configurationFile=config/logback.xml",
                   "-cp", classpath, "com.aionemu.gameserver.services.ai.memory.CompanionW0RuntimeProbe"]
        for phase in PHASES:
            print("W0 START " + phase, flush=True)
            started = time.monotonic()
            log = output / (phase + ".log")
            with log.open("w", encoding="utf-8") as stream:
                process = subprocess.run(command + [phase], cwd=runtime, stdout=stream, stderr=subprocess.STDOUT, timeout=240)
            content = log.read_text(encoding="utf-8", errors="replace")
            passed = process.returncode == 0 and "W0_RUNTIME_PASS phase=" + phase in content and "W0_RUNTIME_FAIL" not in content
            report["phases"].append({"phase": phase, "passed": passed, "seconds": round(time.monotonic() - started, 2),
                                     "log_sha256": hashlib.sha256(log.read_bytes()).hexdigest(),
                                     "load": [line for line in content.splitlines() if line.startswith("W0_LOAD ")]})
            print("W0 " + ("PASS " if passed else "FAIL ") + phase, flush=True)
            if not passed:
                raise RuntimeError("W0 phase failed; inspect " + str(log))
        report["status"] = "PASS"
    except BaseException as error:
        report["status"] = "FAIL"
        report["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        already_failing = sys.exc_info()[0] is not None
        cleanup_error = None
        try:
            if created:
                info = json.loads(subprocess.check_output(["docker", "inspect", name]))[0]
                if info["Name"] != "/" + name or info["Config"]["Labels"].get("codex.ai.acceptance") != "W0" or not info["HostConfig"]["Tmpfs"].get("/var/lib/mysql"):
                    raise RuntimeError("Refusing cleanup: container identity mismatch")
                subprocess.run(["docker", "stop", "-t", "10", name], check=True, capture_output=True, timeout=30)
                report["container_stopped"] = True
        except BaseException as error:
            cleanup_error = error
            report["status"] = "FAIL"
            report["cleanup_error"] = type(error).__name__ + ": " + str(error)
        finally:
            config.write_text(config.read_text(encoding="utf-8").replace(password, "REDACTED_DISPOSABLE_PASSWORD"), encoding="utf-8")
            (output / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
            print("W0 report: " + str(output / "report.json"), flush=True)
        if cleanup_error is not None and not already_failing:
            raise RuntimeError("Test container cleanup failed; see report") from cleanup_error


if __name__ == "__main__":
    main()
