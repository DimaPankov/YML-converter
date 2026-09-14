#!/usr/bin/env python3
"""Package the current Compose JVM build with verified Windows binaries using NSIS."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "2.2.2"
DOWNLOADS = ROOT / "build/windows-downloads"
APP = ROOT / "composeApp/build/compose/binaries/main/app/YMLStudio.app/Contents/app"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    os.environ["LC_ALL"] = "en_US.UTF-8"
    metadata = json.loads((DOWNLOADS / "temurin.json").read_text())[0]
    runtime = DOWNLOADS / "temurin17-windows-x64.zip"
    skiko = DOWNLOADS / "skiko-awt-runtime-windows-x64-0.9.37.4.jar"
    assert digest(runtime) == metadata["binary"]["package"]["checksum"], "JRE checksum mismatch"
    assert digest(skiko) == (DOWNLOADS / "skiko.sha256").read_text().strip(), "Skiko checksum mismatch"
    stage = Path(tempfile.mkdtemp(prefix="payload-", dir=DOWNLOADS))
    jars = stage / "app"
    jars.mkdir()
    for jar in APP.glob("*.jar"):
        if not jar.name.startswith("skiko-awt-runtime-"):
            shutil.copy2(jar, jars / jar.name)
    assert len(list(jars.glob("composeApp-desktop-*.jar"))) == 1
    source_jar = ROOT / "composeApp/build/libs/composeApp-desktop.jar"
    # Compose repacks ZIP timestamps/compression; compare uncompressed contents.
    with zipfile.ZipFile(source_jar) as current, zipfile.ZipFile(next(jars.glob("composeApp-desktop-*.jar"))) as packaged:
        assert set(current.namelist()) == set(packaged.namelist()), "Stale app distribution"
        assert all(current.read(n) == packaged.read(n) for n in current.namelist()), "Stale app distribution"
    shutil.copy2(skiko, jars / skiko.name)
    if (APP / "resources").exists():
        shutil.copytree(APP / "resources", jars / "resources")
    with zipfile.ZipFile(runtime) as z:
        assert z.testzip() is None
        for info in z.infolist():
            parts = Path(info.filename).parts[1:]
            if not parts or info.is_dir():
                continue
            assert ".." not in parts
            dest = stage / "runtime" / Path(*parts)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(z.read(info))
    assert (stage / "runtime/bin/javaw.exe").read_bytes()[:2] == b"MZ"
    with zipfile.ZipFile(skiko) as z:
        assert z.testzip() is None
        assert any(n.endswith(".dll") for n in z.namelist())
    subprocess.run(["makensis", "-V2", "-DOUTPUT_FILE=" + str(stage / "YMLStudio.exe"),
                    str(ROOT / "tools/windows/launcher.nsi")], check=True)
    instructions = """YML Студия 2.2.2 — Windows 10/11 x64

Закройте запущенную YML Студию перед установкой или обновлением.
Запустите установщик. Java включена, отдельная установка не нужна.
Программа доступна через ярлык «YML Студия» на рабочем столе и в меню «Пуск».
Данные: %LOCALAPPDATA%\\YMLStudio\\data. При удалении программы они сохраняются.
Для переноса каталога с Mac используйте резервную копию ZIP, распакуйте её
на Windows и выберите в приложении «Настройки каталога → Импорт project.json».

Установщик собран на macOS с Windows JRE и Windows Skiko.
Фактический запуск и установка на Windows в среде сборки не проверены.
"""
    (stage / "Инструкция.txt").write_text(instructions, encoding="utf-8-sig")
    manifest = {"version": VERSION, "platform": "windows-x64", "runtime": metadata["version"],
                "runtime_sha256": digest(runtime), "skiko_sha256": digest(skiko),
                "app_sha256": digest(source_jar), "windows_execution_tested": False,
                "files": {p.relative_to(stage).as_posix(): digest(p) for p in sorted(stage.rglob("*")) if p.is_file()}}
    (stage / "build-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    output = ROOT / "dist" / f"YMLStudio-Setup-{VERSION}-windows-x64.exe"
    subprocess.run(["makensis", "-V2", "-DAPP_SOURCE=" + str(stage), "-DOUTPUT_FILE=" + str(output),
                    str(ROOT / "tools/windows/installer.nsi")], check=True)
    assert output.read_bytes()[:2] == b"MZ"
    output.with_suffix(".exe.sha256").write_text(digest(output) + "  " + output.name + "\n")
    shutil.copy2(stage / "build-manifest.json", output.with_suffix(".manifest.json"))
    (ROOT / "dist/Windows-2.2.2-Инструкция.txt").write_text(instructions, encoding="utf-8-sig")
    print(f"Built {output}\nBytes: {output.stat().st_size}\nSHA256: {digest(output)}", flush=True)


if __name__ == "__main__":
    main()
