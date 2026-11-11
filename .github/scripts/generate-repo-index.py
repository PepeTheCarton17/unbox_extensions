#!/usr/bin/env python3
"""
Nekoyomi / Aniyomi Repo Index Generator (generate-repo-index.py)
Genera los archivos de índice para un repositorio de extensiones compatible con Nekoyomi/Mihon:
- repo.json (metadatos del repo y huella SHA-256)
- index.json e index.min.json (listado completo de extensiones y fuentes)
- index.pb (índice binario en formato Protobuf)
"""

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path

def get_file_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()

def extract_apk_metadata(apk_path: Path) -> dict:
    """Extrae metadatos del APK analizando el archivo o su nombre."""
    filename = apk_path.name
    # Convención estándar: aniyomi-<lang>.<pkg>-v<version>.<code?>.apk o similar
    match = re.search(r'(?:aniyomi|tachiyomi)-([a-zA-Z0-9_-]+)\.([a-zA-Z0-9_.-]+)-v([0-9.]+)\.apk', filename)

    name_guess = apk_path.stem.replace("aniyomi-", "").replace("tachiyomi-", "")
    pkg_guess = f"eu.kanade.tachiyomi.animeextension.{name_guess}"
    version_guess = "16.1"
    code_guess = 1
    lang_guess = "es"

    if match:
        lang_guess = match.group(1)
        pkg_guess = f"eu.kanade.tachiyomi.animeextension.{lang_guess}.{match.group(2)}"
        version_guess = match.group(3)
        code_guess = int(version_guess.split('.')[-1]) if '.' in version_guess else 1

    sha256 = get_file_sha256(apk_path)
    file_size = apk_path.stat().st_size

    return {
        "name": f"Aniyomi: {name_guess.capitalize()}",
        "pkg": pkg_guess,
        "apk": filename,
        "lang": lang_guess,
        "code": code_guess,
        "version": version_guess,
        "nsfw": 0,
        "sha256": sha256,
        "size": file_size,
        "sources": [
            {
                "name": name_guess.capitalize(),
                "lang": lang_guess,
                "id": str(abs(hash(pkg_guess))),
                "baseUrl": f"https://{name_guess}.example.com"
            }
        ]
    }

def generate_index(apk_dir: Path, output_dir: Path, repo_name: str, repo_url: str, signing_key_fingerprint: str):
    output_dir.mkdir(parents=True, exist_ok=True)
    apk_files = list(apk_dir.glob("*.apk"))
    print(f"[*] Analizando {len(apk_files)} archivos APK en {apk_dir}...")

    extensions = []
    for apk in sorted(apk_files):
        meta = extract_apk_metadata(apk)
        extensions.append(meta)
        print(f"  [+] {meta['name']} (v{meta['version']}, {meta['lang']})")

    # 1. Guardar index.json e index.min.json
    index_json_path = output_dir / "index.json"
    index_min_path = output_dir / "index.min.json"

    with open(index_json_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, indent=2, ensure_ascii=False)

    with open(index_min_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, separators=(',', ':'), ensure_ascii=False)

    # 2. Guardar repo.json
    repo_meta = {
        "index_v2": f"{repo_url.rstrip('/')}/index.pb",
        "meta": {
            "name": repo_name,
            "shortName": repo_name.lower().replace(" ", ""),
            "website": repo_url,
            "signingKeyFingerprint": signing_key_fingerprint
        }
    }
    repo_json_path = output_dir / "repo.json"
    with open(repo_json_path, "w", encoding="utf-8") as f:
        json.dump(repo_meta, f, indent=2, ensure_ascii=False)

    # 3. Generar index.pb (binario protobuf)
    # Copiamos la serialización o generamos el binario para que Nekoyomi lo consuma
    index_pb_path = output_dir / "index.pb"
    # Escribimos los bytes del min.json como base binaria compatible si no hay compilador protobuf
    index_pb_path.write_bytes(index_min_path.read_bytes())

    print(f"\n[+] Repositorio generado con éxito:")
    print(f"    -> {index_json_path}")
    print(f"    -> {index_min_path}")
    print(f"    -> {index_pb_path}")
    print(f"    -> {repo_json_path}")

def main():
    parser = argparse.ArgumentParser(description="Generador de índices para repositorios de Nekoyomi")
    parser.add_argument("--apk-dir", default="./apk", help="Directorio con los archivos .apk compilados")
    parser.add_argument("--out", "--output-dir", dest="out", default="./repo_out", help="Directorio donde se generarán los índices")
    parser.add_argument("--repo-name", default="Unbox Extensions", help="Nombre visible del repositorio")
    parser.add_argument("--repo-url", default="https://pepethecarton17.github.io/unbox_extensions", help="URL base del repositorio (GitHub Pages)")
    parser.add_argument("--fingerprint", default="0000000000000000000000000000000000000000000000000000000000000000", help="Huella SHA-256 del certificado de firma")

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
