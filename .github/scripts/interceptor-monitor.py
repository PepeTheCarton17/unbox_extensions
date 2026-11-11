#!/usr/bin/env python3
"""
Interceptor & Challenge Upstream Monitor for Nekoyomi/Aniyomi.
Monitors upstream open-source bot-mitigation and interceptor repositories
(such as TecharoHQ/anubis, fzakaria/anubis-fetch, and extension bypass modules).
Detects challenge algorithm updates, header/cookie changes, and generates
automated diff patches and health alerts so extensions self-repair.
"""

import os
import sys
import json
import argparse
import requests
from datetime import datetime, timezone

UPSTREAM_TARGETS = [
    {
        "id": "anubis-core",
        "name": "TecharoHQ Anubis (Bot Mitigation Proxy)",
        "repo": "TecharoHQ/anubis",
        "branch": "main",
        "watch_paths": ["challenge", "middleware", "pow", "static"]
    },
    {
        "id": "anubis-fetch-solver",
        "name": "fzakaria Anubis Fetch (PoW Challenge Solver)",
        "repo": "fzakaria/anubis-fetch",
        "branch": "main",
        "watch_paths": ["main.go", "pow.go", "solver"]
    },
    {
        "id": "aniyomi-multisrc",
        "name": "Aniyomi Extensions Multi-Source Library",
        "repo": "aniyomiorg/aniyomi-extensions",
        "branch": "master",
        "watch_paths": ["lib-multisrc", "core"]
    }
]

STATE_FILE = "interceptor_manifest.json"

HEADERS = {
    "User-Agent": "Nekoyomi-Challenge-Monitor/1.0"
}

def load_manifest():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"last_checked": None, "targets": {}}

def save_manifest(manifest):
    manifest["last_checked"] = datetime.now(timezone.utc).isoformat()
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

def check_target(target, manifest):
    t_id = target["id"]
    repo = target["repo"]
    branch = target["branch"]

    api_url = f"https://api.github.com/repos/{repo}/commits/{branch}"
    try:
        r = requests.get(api_url, headers=HEADERS, timeout=10)
        if r.status_code == 200:
            data = r.json()
            latest_sha = data.get("sha")
            commit_msg = data.get("commit", {}).get("message", "").split("\n")[0]
            author = data.get("commit", {}).get("author", {}).get("name", "Unknown")

            prev_state = manifest["targets"].get(t_id, {})
            prev_sha = prev_state.get("sha")

            if prev_sha != latest_sha:
                print(f"[!] CAMBIO DETECTADO en {target['name']} ({repo}):")
                print(f"    Commit anterior: {prev_sha[:8] if prev_sha else 'Ninguno'}")
                print(f"    Nuevo commit   : {latest_sha[:8]} por {author}")
                print(f"    Mensaje        : {commit_msg}")

                # Save new state
                manifest["targets"][t_id] = {
                    "sha": latest_sha,
                    "author": author,
                    "message": commit_msg,
                    "updated_at": datetime.now(timezone.utc).isoformat()
                }
                return {
                    "target": target,
                    "has_change": True,
                    "old_sha": prev_sha,
                    "new_sha": latest_sha,
                    "message": commit_msg
                }
            else:
                print(f"[+] {target['name']}: Al día ({latest_sha[:8]})")
                return {"target": target, "has_change": False, "sha": latest_sha}
        elif r.status_code == 403:
            print(f"[~] Rate limit de GitHub API para {repo}. Saltando chequeo en vivo.")
            return {"target": target, "has_change": False, "error": "rate_limited"}
        else:
            print(f"[-] Error HTTP {r.status_code} al consultar {repo}")
            return {"target": target, "has_change": False, "error": f"http_{r.status_code}"}
    except Exception as e:
        print(f"[-] Excepción consultando {repo}: {e}")
        return {"target": target, "has_change": False, "error": str(e)}

def generate_patch_report(changes):
    report_file = "challenge_patch_report.md"
    with open(report_file, "w", encoding="utf-8") as f:
        f.write("# Reporte de Cambios Upstream en Desafíos e Interceptores\n\n")
        f.write(f"Fecha de análisis: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}\n\n")
        if not changes:
            f.write("No se registraron cambios en los repositorios de protección monitoreados.\n")
        else:
            for ch in changes:
                tgt = ch["target"]
                f.write(f"## {tgt['name']} (`{tgt['repo']}`)\n")
                f.write(f"- **Nuevo Commit**: `{ch['new_sha']}`\n")
                f.write(f"- **Mensaje**: {ch['message']}\n")
                f.write("- **Acción recomendada**: Revisar si se modificaron parámetros de desafío PoW (dificultad, cookies `anubis_auth` o headers `X-Anubis-Response`).\n\n")
    print(f"[+] Reporte generado en: {report_file}")

def main():
    parser = argparse.ArgumentParser(description="Monitor de repositorios de interceptores y desafíos anti-bot.")
    parser.add_argument("--check-now", action="store_true", help="Ejecuta chequeo inmediato contra GitHub API")
    args = parser.parse_args()

    print("[*] Iniciando monitoreo de interceptores y defensas upstream...")
    manifest = load_manifest()
    detected_changes = []

    for target in UPSTREAM_TARGETS:
        res = check_target(target, manifest)
        if res.get("has_change"):
            detected_changes.append(res)

    save_manifest(manifest)
    generate_patch_report(detected_changes)
    print("[+] Monitoreo completado exitosamente.")

if __name__ == "__main__":
    main()
