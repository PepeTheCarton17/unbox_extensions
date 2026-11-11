# Unbox Extensions Repository

Repositorio oficial de extensiones para **Nekoyomi** y **Aniyomi** de [PepeTheCarton17](https://github.com/PepeTheCarton17).

## 🚀 Cómo agregar este repositorio a Nekoyomi / Aniyomi

1. Abre **Nekoyomi** o **Aniyomi**.
2. Ve a **Más** → **Ajustes** → **Explorar** → **Repositorios de extensiones**.
3. Presiona el botón **+** y agrega la siguiente URL:
   ```
   https://pepethecarton17.github.io/unbox_extensions/index.min.json
   ```

---

## 📦 Extensiones Incluidas

### 1. **AnimeAV1 (`es`)**
- **Fuente base:** `https://animeav1.com`
- **Idioma:** Español latino / castellano
- **Arquitectura:** Soporte nativo para estado hidratado de SvelteKit y tarjetas SSR.
- **Extractores de Video Integrados:**
  - `Zilla / HLS`: Extracción directa de playlists m3u8 (`player.zilla-networks.com`) con bypass de cabeceras Referer/Origin.
  - `Voe`: Extracción de flujos HLS y MP4 en calidad variable.
  - `MP4Upload`: Reproducción y descarga en calidad original.
  - `Byse`: Mapeo de mirrors StreamWish / Vite.
- **Protecciones y Mitigación de Bots:**
  - `AnubisInterceptor`: Solucionador nativo de desafíos Proof-of-Work (PoW) SHA-256 para evitar bloqueos por proxies y firewalls anti-crawler.
  - Integración transparente con `CloudflareInterceptor` de Nekoyomi.
- **Recursos de Interfaz:**
  - Conjunto completo de iconos Android (`mipmap-mdpi`, `mipmap-hdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi`).

---

## 🛠️ Automatización y Auto-Reparación

Este repositorio incluye flujos de trabajo de GitHub Actions automatizados:
- **`build_push.yml`**: Compila los APKs de las extensiones, los firma y genera automáticamente el índice `index.min.json` y `repo.json` publicándolos en GitHub Pages.
- **`health_check.yml`**: Monitor nocturno que supervisa cambios en los repositorios de interceptores y desafíos de código abierto (ej. Anubis en TecharoHQ / fzakaria) para aplicar parches de auto-reparación antes de que se rompan las fuentes.
