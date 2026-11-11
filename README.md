# Unbox Extensions Repository

Repositorio de extensiones para **Nekoyomi** y **Aniyomi** de [PepeTheCarton17](https://github.com/PepeTheCarton17).

---

## 🚀 Cómo agregar este repositorio a Nekoyomi / Aniyomi

1. Abre **Nekoyomi** o **Aniyomi**.
2. Ve a **Más** → **Ajustes** → **Explorar** → **Repositorios de extensiones**.
3. Presiona el botón **+** y pega **cualquiera** de estas dos URLs:

### Opción 1: Enlace directo GitHub Raw (Recomendado - Funciona inmediatamente)
```
https://raw.githubusercontent.com/PepeTheCarton17/unbox_extensions/gh-pages/index.min.json
```

### Opción 2: Enlace de GitHub Pages
```
https://pepethecarton17.github.io/unbox_extensions/index.min.json
```
*(Para usar la Opción 2, asegúrate de tener activo GitHub Pages en tu repo: `Settings` → `Pages` → `Branch: gh-pages` / `root` → `Save`)*.

---

## 📦 Extensiones Disponibles

### 1. **AnimeAV1 (`es`)**
- **Fuente:** `https://animeav1.com`
- **Idioma:** Español
- **Arquitectura:** Extracción de estado hidratado en SSR (SvelteKit) y tarjetas dinámicas.
- **Servidores de Video Soportados:**
  - `Zilla / HLS`: Playlists HLS directas con bypass de cabeceras Referer/Origin.
  - `Voe`: Flujos en calidad variable.
  - `MP4Upload`: Videos directos.
  - `Byse`: Mirrors de reproducción.
- **Protecciones y Desafíos:**
  - `AnubisInterceptor`: Solucionador Proof-of-Work (PoW) SHA-256 para evitar bloqueos por proxies y firewalls.
- **Iconos:** Mipmaps Android completos (`mdpi` a `xxxhdpi`).

---

## ⚙️ Flujos de Trabajo Automatizados (CI/CD)

- **`build_push.yml`**: Compila los APKs en GitHub Actions con Gradle 8.5 y JDK 17, firma con keystore debug y despliega el repositorio a la rama `gh-pages`.
- **`health_check.yml`**: Monitor nocturno que comprueba la salud de las fuentes y detecta actualizaciones en repositorios upstream de interceptores (`TecharoHQ/anubis`, `fzakaria/anubis-fetch`).
