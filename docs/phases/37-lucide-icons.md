# Phase 37 — All of lucide for app icons

Status: **built, compiled and unit-tested (JVM + Robolectric); the drawing was never run on a device or emulator.**
iOS read from `main`: `Sources/PeelUI/WorkspaceAppIcon.swift` (`LucideNames`, the alias table), `Tests/PeelUITests/WorkspaceAppIconTests.swift`;
iOS draws the `LucideSwift` package (0.9.22, ISC). Supersedes the Material table of phase 36 step 8.

## Built
- **The asset** `app/src/main/assets/lucide/icons.json` (+ `LICENSE`, lucide's ISC text, beside it): **2108 icons, 354,672 bytes** minified (under the 600 KB bar, so no gzip;
  the APK packs it smaller). Written by `tools/lucide-convert.py <npm pack lucide-static dir> <out> app/.../LucideNames.kt` from **lucide-static 1.73.0** (the newest;
  the iOS alias table names icons such as `face-angry` and `file-exclamation-point` that only a recent lucide has). Each icon is its SVG primitives
  (`["p",d]`, `["c",cx,cy,r,filled]`, `["r",…]`, `["l",…]`, `["e",…]`, `["pl",…]`, `["pg",…]`); an icon identical to an earlier one is `"@name"`. The old alias files lucide-static
  still ships (`home`, `bar-chart-3`) are left out, so they resolve through iOS's table to the current name.
- `engine/SvgPath.kt` (pure) — path data → absolute `Seg`s (Move/Line/Cubic/Quad/Close): M L H V C S Q T A Z, relative forms, implicit repeats, arc flags run together, exponents; an arc becomes
  cubics (SVG 1.1 F.6, one per quarter turn); malformed data ends the path rather than throwing.
- `engine/LucideSet.kt` — `LucideSet.parse` (the asset, `org.json`), `LucidePrimitives` (circle/ellipse/rect incl. rounded/line/polyline/polygon → path data → `SvgPath`), `LucideShape`/`LucidePath`,
  `Lru` (pure least-recently-used map).
- `engine/LucideNames.kt` — iOS's rules kept: trim, lower-case, punctuation-insensitive key, **the whole iOS alias table** (251 entries, copied from `WorkspaceAppIcon.swift`), unknown/none → `layout-grid`.
  Now takes the set's name index instead of a hard-coded `SUPPORTED`.
- `ui/apps/AppIcons.kt` — loads the asset once (`AppIcons.load`, on `Dispatchers.IO` from the composable), builds an `ImageVector` per icon (24×24, stroke 2, round caps and joins, black, so the
  `Icon` tint colours it; the few `fill="currentColor"` dots are filled too), cached in a 256-entry `Lru`. `WorkspaceAppIcon` is unchanged for `AppsScreen`'s neutral tile; it shows an empty
  square for the instant before the asset is read. The Material table (`Icons.Filled.*`) is gone.

## Differences from iOS
- lucide-static 1.73.0, not LucideSwift 0.9.22's snapshot: a name only in the newer one draws here and is the grid on iOS (and the reverse for an icon lucide dropped).
- iOS's `repeat`/`package` "Icon"-suffix lookup is a Swift-keyword quirk of the package's case names; not needed (names are plain strings here).
- lucide aliases that are not in iOS's table but ship as files in lucide-static (other than the iOS-table ones) draw here; on iOS they would be the grid unless LucideSwift has them.
- The icon is empty for the first moments while the asset is parsed; iOS's is static data.

## Tests
`SvgPathTest` (10: lines/relative/close, implicit lineto and separators, S and T reflection, arcs to cubics incl. flags run together and scaled radii, malformed, exponents),
`LucideSetTest` (14, Robolectric: asset size/licence/count, **every icon parses** to non-empty paths in the view box, every iOS alias lands on an icon, goldens `house`, `chart-bar`, `circle-user`, `settings`
by command counts, primitives → paths, `@` entries, name rules incl. aliases and the grid fallback, the old Material table's names still resolve, LRU).
### UNTESTED
- Real drawing on a device or emulator (compile only): stroke look, tint, sizes; scrolling a long Apps list (each row builds at most one vector, cached); parse time of the asset on a low-end phone.
