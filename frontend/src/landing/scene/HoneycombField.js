import {
  Color,
  CylinderGeometry,
  InstancedMesh,
  MeshStandardMaterial,
  Object3D
} from 'three'
import { PALETTE } from './palette.js'

const FIELD_DEPTH = 16
const RIBBON_FALLOFF = 3.2
const BASE_HEIGHT = 0.08
const GLOW_HEIGHT = 0.32

/**
 * Hexagonal comb under the timeline. Each cell glows according to the real
 * surprise value recorded at its position along the time axis.
 */
export class HoneycombField {
  constructor(space, maxSurprise, cellRadius) {
    this.material = new MeshStandardMaterial({ roughness: 0.82, metalness: 0.08 })
    this.geometry = new CylinderGeometry(cellRadius * 0.94, cellRadius * 0.94, 1, 6)
    this.geometry.translate(0, 0.5, 0)

    const cells = HoneycombField.layoutCells(space.length + 8, FIELD_DEPTH, cellRadius)
    this.mesh = new InstancedMesh(this.geometry, this.material, cells.length)
    this.mesh.receiveShadow = false

    const dummy = new Object3D()
    const comb = new Color(PALETTE.comb)
    const honey = new Color(PALETTE.honey)
    const cellColor = new Color()

    cells.forEach(([x, z], index) => {
      const surprise = space.surpriseNearX(x)
      const glow = space.normalizedSurprise(surprise, maxSurprise) * Math.exp(-Math.abs(z) / RIBBON_FALLOFF)
      dummy.position.set(x, 0, z)
      dummy.scale.set(1, BASE_HEIGHT + glow * GLOW_HEIGHT, 1)
      dummy.updateMatrix()
      this.mesh.setMatrixAt(index, dummy.matrix)
      this.mesh.setColorAt(index, cellColor.copy(comb).lerp(honey, 0.12 + glow * 0.55))
    })
    this.mesh.instanceMatrix.needsUpdate = true
    this.mesh.instanceColor.needsUpdate = true
    this.setIntensity(0)
  }

  static layoutCells(width, depth, radius) {
    const columnSpacing = radius * 1.5
    const rowSpacing = radius * Math.sqrt(3)
    const cells = []
    for (let x = -width / 2; x <= width / 2; x += columnSpacing) {
      const column = Math.round((x + width / 2) / columnSpacing)
      const rowShift = column % 2 === 0 ? 0 : rowSpacing / 2
      for (let z = -depth / 2; z <= depth / 2; z += rowSpacing) {
        cells.push([x, z + rowShift])
      }
    }
    return cells
  }

  setIntensity(value) {
    this.material.color.setScalar(0.25 + value * 0.75)
  }

  dispose() {
    this.geometry.dispose()
    this.material.dispose()
  }
}
