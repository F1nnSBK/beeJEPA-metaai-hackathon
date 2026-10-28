import { BoxGeometry, Color, InstancedMesh, MeshBasicMaterial, Object3D } from 'three'
import { PALETTE } from './palette.js'

const PILLAR_WIDTH = 0.09
const STAGGER = 0.35

/**
 * One light pillar per recorded peak event. Height equals the event's real
 * surprise score; the strongest event is the saccade target and is drawn in vermilion.
 */
export class EventPillars {
  constructor(space, events, focusEventId) {
    this.space = space
    this.events = [...events].sort((a, b) => a.t - b.t)
    this.geometry = new BoxGeometry(PILLAR_WIDTH, 1, PILLAR_WIDTH)
    this.geometry.translate(0, 0.5, 0)
    this.material = new MeshBasicMaterial({ transparent: true, opacity: 0.9 })
    this.mesh = new InstancedMesh(this.geometry, this.material, this.events.length)
    this.dummy = new Object3D()

    const honey = new Color(PALETTE.honey)
    const vermilion = new Color(PALETTE.vermilion)
    this.events.forEach((event, index) => {
      this.mesh.setColorAt(index, event.id === focusEventId ? vermilion : honey)
    })
    this.mesh.instanceColor.needsUpdate = true
    this.setGrowth(0)
  }

  /** Grows the pillars left to right in recording order; 0 hides, 1 shows full height. */
  setGrowth(value) {
    const count = this.events.length
    this.events.forEach((event, index) => {
      const delay = (index / Math.max(1, count - 1)) * STAGGER
      const local = Math.min(1, Math.max(0, (value - delay) / (1 - STAGGER)))
      this.dummy.position.set(this.space.xAt(event.t), 0, 0)
      this.dummy.scale.set(1, Math.max(0.0001, this.space.heightOf(event.s) * local), 1)
      this.dummy.updateMatrix()
      this.mesh.setMatrixAt(index, this.dummy.matrix)
    })
    this.mesh.instanceMatrix.needsUpdate = true
    this.mesh.visible = value > 0
  }

  dispose() {
    this.geometry.dispose()
    this.material.dispose()
  }
}
