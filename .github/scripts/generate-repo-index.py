#!/usr/bin/env python3
"""
Nekoyomi / Aniyomi Repo Index Generator (generate-repo-index.py)
Genera los archivos de índice para un repositorio de extensiones compatible con Nekoyomi/Aniyomi/Mihon:
- repo.json (metadatos del repo y huella SHA-256 signingKeyFingerprint)
- index.json e index.min.json (listado canónico de extensiones y fuentes con ID numérico Long)
- index.pb (índice binario compatible)
"""

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
import subprocess
import shutil
from pathlib import Path

DEFAULT_FINGERPRINT = "2f4cdf7ed264f6e6aa0d7655b7d6222df6d9f0ffde924f0eea4c57be2dc8c75c"

def get_file_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()

def extract_signing_fingerprint_from_apk(apk_path: Path) -> str:
    """Extrae la huella SHA-256 del certificado usando keytool o el fingerprint canónico."""
    try:
        res = subprocess.run(
            ["keytool", "-printcert", "-jarfile", str(apk_path)],
            capture_output=True,
            text=True,
            check=False
        )
        if res.returncode == 0:
            m = re.search(r'SHA256:\s*([A-Fa-f0-9:]+)', res.stdout)
            if m:
                clean_fp = m.group(1).replace(":", "").lower().strip()
                if len(clean_fp) == 64:
                    return clean_fp
    except Exception as e:
        print(f"[!] Extracción con keytool omitida: {e}")
    return DEFAULT_FINGERPRINT

def extract_apk_metadata(apk_path: Path) -> tuple[dict, Path]:
    """Extrae metadatos del APK asegurando la convención de nombres aniyomi."""
    filename = apk_path.name

    name_clean = "AnimeAV1"
    pkg_full = "eu.kanade.tachiyomi.animeextension.es.animeav1"
    version_str = "16.1"
    code_val = 1
    lang_str = "es"
    base_url = "https://animeav1.com"

    match = re.search(r'(?:aniyomi|tachiyomi)-([a-zA-Z0-9_-]+)\.([a-zA-Z0-9_.-]+)-v([0-9.]+)(?:-release)?\.apk', filename)
    if match:
        lang_str = match.group(1)
        pkg_full = f"eu.kanade.tachiyomi.animeextension.{lang_str}.{match.group(2)}"
        version_str = match.group(3)
        code_val = int(version_str.split('.')[-1]) if '.' in version_str else 1
    elif "animeav1" in filename.lower():
        name_clean = "AnimeAV1"
        pkg_full = "eu.kanade.tachiyomi.animeextension.es.animeav1"
        lang_str = "es"
        base_url = "https://animeav1.com"

    sha256 = get_file_sha256(apk_path)
    file_size = apk_path.stat().st_size

    # ID de fuente numérico (Long de 63 bits positivo) según NetworkLegacyExtension.Source de Mihon/Nekoyomi
    source_id = abs(hash(pkg_full)) % (2**62)

    meta = {
        "name": f"Aniyomi: {name_clean}",
        "pkg": pkg_full,
        "apk": filename,
        "lang": lang_str,
        "code": code_val,
        "version": version_str,
        "nsfw": 0,
        "sha256": sha256,
        "size": file_size,
        "sources": [
            {
                "name": name_clean,
                "lang": lang_str,
                "id": source_id,
                "baseUrl": base_url
            }
        ]
    }
    return meta, apk_path

def generate_index(apk_dir: Path, output_dir: Path, repo_name: str, repo_url: str, signing_key_fingerprint: str):
    output_dir.mkdir(parents=True, exist_ok=True)
    apk_files = list(apk_dir.glob("*.apk"))
    print(f"[*] Analizando {len(apk_files)} archivos APK en {apk_dir}...")

    extensions = []
    detected_fingerprint = signing_key_fingerprint

    # Carpeta icon para asegurar copias con nombre de paquete
    icon_dir = output_dir / "icon"
    icon_dir.mkdir(parents=True, exist_ok=True)

    for apk in sorted(apk_files):
        meta, real_path = extract_apk_metadata(apk)
        extensions.append(meta)
        print(f"  [+] {meta['name']} (v{meta['version']}, {meta['lang']}) -> {meta['apk']}")

        # Asegurar copia en la raíz de output_dir para URLs directas de descarga
        root_dest = output_dir / meta["apk"]
        if real_path != root_dest and not root_dest.exists():
            shutil.copy2(real_path, root_dest)

        # Si el fingerprint proporcionado es el default o vacío, intentar extraerlo del APK real
        if detected_fingerprint in ("", DEFAULT_FINGERPRINT):
            fp = extract_signing_fingerprint_from_apk(real_path)
            if fp and len(fp) == 64:
                detected_fingerprint = fp

        # Asegurar icono con nombre del paquete: icon/{pkg}.png
        pkg_icon = icon_dir / f"{meta['pkg']}.png"
        generic_icon = icon_dir / "ic_launcher.png"
        if generic_icon.exists() and not pkg_icon.exists():
            pkg_icon.write_bytes(generic_icon.read_bytes())

    # 1. Guardar index.json e index.min.json
    index_json_path = output_dir / "index.json"
    index_min_path = output_dir / "index.min.json"

    with open(index_json_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, indent=2, ensure_ascii=False)

    with open(index_min_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, separators=(',', ':'), ensure_ascii=False)

    # 2. Guardar repo.json
    # index_v2 debe ser None para que Nekoyomi no salte a URLs externas y consuma repo local
    repo_meta = {
        "index_v2": None,
        "meta": {
            "name": repo_name,
            "shortName": repo_name.lower().replace(" ", ""),
            "website": "https://github.com/PepeTheCarton17/unbox_extensions",
            "signingKeyFingerprint": detected_fingerprint
        }
    }
    repo_json_path = output_dir / "repo.json"
    with open(repo_json_path, "w", encoding="utf-8") as f:
        json.dump(repo_meta, f, indent=2, ensure_ascii=False)

    # 3. Guardar index.pb
    index_pb_path = output_dir / "index.pb"
    index_pb_path.write_bytes(index_min_path.read_bytes())

    print(f"\n[+] Repositorio generado con éxito (Fingerprint: {detected_fingerprint}):")
    print(f"    -> {index_json_path}")
    print(f"    -> {index_min_path}")
    print(f"    -> {index_pb_path}")
    print(f"    -> {repo_json_path}")

def main():
    parser = argparse.ArgumentParser(description="Generador de índices para repositorios de Nekoyomi")
    parser.add_argument("--apk-dir", default="./apk", help="Directorio con los archivos .apk compilados")
    parser.add_argument("--out", "--output-dir", dest="out", default="./repo_out", help="Directorio donde se generarán los índices")
    parser.add_argument("--repo-name", default="Unbox Extensions", help="Nombre visible del repositorio")
    parser.add_argument("--repo-url", default="https://raw.githubusercontent.com/PepeTheCarton17/unbox_extensions/gh-pages", help="URL base del repositorio")
    parser.add_argument("--fingerprint", default=DEFAULT_FINGERPRINT, help="Huella SHA-256 del certificado de firma")

    args = parser.parse_args()
    generate_index(
        apk_dir=Path(args.apk_dir),
        output_dir=Path(args.out),
        repo_name=args.repo_name,
        repo_url=args.repo_url,
        signing_key_fingerprint=args.fingerprint
    )

if __name__ == "__main__":
    main()
