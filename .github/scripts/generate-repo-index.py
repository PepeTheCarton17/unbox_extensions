#!/usr/bin/env python3
"""
Nekoyomi / Aniyomi Repo Index Generator (generate-repo-index.py)
Genera los archivos de índice para un repositorio de extensiones compatible con Nekoyomi/Aniyomi/Mihon:
- repo.json (metadatos del repo y huella SHA-256 signingKeyFingerprint)
- index.json e index.min.json (listado canónico de extensiones y fuentes)
- index.pb (índice binario compatible)
"""

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path

DEFAULT_FINGERPRINT = "2f4cdf7ed264f6e6aa0d7655b7d6222df6d9f0ffde924f0eea4c57be2dc8c75c"

def get_file_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()

def extract_signing_fingerprint_from_apk(apk_path: Path) -> str:
    """Extrae la huella SHA-256 del certificado X.509 embebido en el APK."""
    try:
        with zipfile.ZipFile(apk_path) as z:
            for item in z.namelist():
                if item.startswith("META-INF/") and (item.endswith(".RSA") or item.endswith(".DSA")):
                    data = z.read(item)
                    idx = data.find(b'\xa0')
                    if idx != -1:
                        cert_start = data.find(b'\x30\x82', idx)
                        if cert_start != -1:
                            length = int.from_bytes(data[cert_start+2:cert_start+4], 'big') + 4
                            cert_der = data[cert_start:cert_start+length]
                            return hashlib.sha256(cert_der).hexdigest()
    except Exception as e:
        print(f"[!] No se pudo extraer certificado de {apk_path}: {e}")
    return DEFAULT_FINGERPRINT

def extract_apk_metadata(apk_path: Path) -> tuple[dict, Path]:
    """Extrae metadatos del APK asegurando la convención de nombres aniyomi."""
    filename = apk_path.name

    # Normalizar nombre a la convención aniyomi-{lang}.{id}-v{ver}.apk
    if not (filename.startswith("aniyomi-") or filename.startswith("tachiyomi-")):
        new_name = "aniyomi-es.animeav1-v16.1.apk"
        new_path = apk_path.parent / new_name
        if apk_path != new_path:
            if new_path.exists():
                new_path.unlink()
            apk_path.rename(new_path)
            apk_path = new_path
            filename = new_name

    name_clean = "AnimeAV1"
    pkg_full = "eu.kanade.tachiyomi.animeextension.es.animeav1"
    version_str = "16.1"
    code_val = 1
    lang_str = "es"
    base_url = "https://animeav1.com"

    match = re.search(r'(?:aniyomi|tachiyomi)-([a-zA-Z0-9_-]+)\.([a-zA-Z0-9_.-]+)-v([0-9.]+)\.apk', filename)
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

    # ID de fuente determinista y positivo de 64 bits para Nekoyomi
    source_id = str(abs(hash(pkg_full)) % (10**18))

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

    for apk in sorted(apk_files):
        meta, real_path = extract_apk_metadata(apk)
        extensions.append(meta)
        print(f"  [+] {meta['name']} (v{meta['version']}, {meta['lang']}) -> {meta['apk']}")

        # Si el fingerprint proporcionado es el default o vacío, intentar extraerlo del APK real
        if detected_fingerprint in ("", DEFAULT_FINGERPRINT):
            fp = extract_signing_fingerprint_from_apk(real_path)
            if fp:
                detected_fingerprint = fp

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
    parser.add_argument("--repo-url", default="https://pepethecarton17.github.io/unbox_extensions", help="URL base del repositorio")
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
