/**
 * Converts the calligraphic act titles into SVG path data at build time.
 *
 * The landing page animates these outlines like ink being drawn with a pen.
 * Pre-computing them keeps font parsing out of the browser bundle entirely.
 *
 * Usage: node scripts/build-calligraphy-paths.mjs
 */
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import opentype from 'opentype.js'

const { parse } = opentype

const FRONTEND_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const FONT_FILE = resolve(
  FRONTEND_ROOT,
  'node_modules/@fontsource/corinthia/files/corinthia-latin-700-normal.woff'
)
const OUTPUT_FILE = resolve(FRONTEND_ROOT, 'src/landing/calligraphy/paths.json')

const WORDS = ['beeJEPA', 'Periphery', 'Surprise', 'Saccade', 'Report']
const FONT_SIZE = 200
const BASELINE = FONT_SIZE
const VIEWBOX_PADDING = 12
const DECIMALS = 1

function toArrayBuffer(buffer) {
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength)
}

function measureBounds(glyphPaths) {
  return glyphPaths.reduce(
    (bounds, glyphPath) => {
      const box = glyphPath.getBoundingBox()
      return {
        x1: Math.min(bounds.x1, box.x1),
        y1: Math.min(bounds.y1, box.y1),
        x2: Math.max(bounds.x2, box.x2),
        y2: Math.max(bounds.y2, box.y2)
      }
    },
    { x1: Infinity, y1: Infinity, x2: -Infinity, y2: -Infinity }
  )
}

function buildWord(font, word) {
  const glyphPaths = font
    .getPaths(word, 0, BASELINE, FONT_SIZE)
    .filter((glyphPath) => glyphPath.commands.length > 0)
  const bounds = measureBounds(glyphPaths)
  const viewBox = [
    bounds.x1 - VIEWBOX_PADDING,
    bounds.y1 - VIEWBOX_PADDING,
    bounds.x2 - bounds.x1 + VIEWBOX_PADDING * 2,
    bounds.y2 - bounds.y1 + VIEWBOX_PADDING * 2
  ].map((value) => Number(value.toFixed(DECIMALS)))

  const glyphs = glyphPaths.map((glyphPath) => glyphPath.toPathData(DECIMALS))
  if (glyphs.some((pathData) => pathData.includes('NaN'))) {
    throw new Error(`Font parser produced invalid coordinates for "${word}".`)
  }
  return { viewBox, glyphs }
}

async function main() {
  const font = parse(toArrayBuffer(await readFile(FONT_FILE)))
  const paths = Object.fromEntries(WORDS.map((word) => [word, buildWord(font, word)]))
  await writeFile(OUTPUT_FILE, JSON.stringify(paths))
  for (const [word, data] of Object.entries(paths)) {
    console.log(`${word.padEnd(14)} glyphs=${data.glyphs.length} viewBox=${data.viewBox.join(' ')}`)
  }
}

await main()
