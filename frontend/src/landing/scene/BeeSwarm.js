import { Color, InstancedMesh, MeshStandardMaterial, Object3D, SphereGeometry, Vector3 } from 'three'
import { PALETTE } from './palette.js'

const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))
const LANDING_STAGGER = 0.045
const DISSOLVE_START = 0.8

function easeInOutCubic(x) {
  return x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2
}

/**
 * Exactly one bee per real SAM 3.1 detection inside the hotspot.
 * While roaming the bees follow the camera's point of interest; during the
 * saccade each bee lands on the centre of the box SAM 3.1 actually found.
 */
export class BeeSwarm {
  constructor(stage) {
    this.targets = stage.focus.samBoxes.map(([x1, y1, x2, y2]) => ({
      position: stage.worldPointAt((x1 + x2) / 2, (y1 + y2) / 2).add(new Vector3(0, 0, 0.02)),
      length: (Math.max(x2 - x1, y2 - y1) / stage.focus.frame.width) * stage.width * 0.5,
      angle: x2 - x1 >= y2 - y1 ? 0 : Math.PI / 2
    }))

    this.geometry = new SphereGeometry(1, 12, 8)
    this.material = new MeshStandardMaterial({
      color: PALETTE.honey,
      emissive: new Color(PALETTE.honey),
      emissiveIntensity: 0.45,
      roughness: 0.5
    })
    this.mesh = new InstancedMesh(this.geometry, this.material, this.targets.length)
    this.mesh.frustumCulled = false
    this.dummy = new Object3D()
    this.roamCenter = new Vector3()
    this.roamRadius = 1
    this.landing = 0
    this.previous = this.targets.map(() => new Vector3())
    this.scratchRoam = new Vector3()
    this.scratchVelocity = new Vector3()
  }

  setRoam(center, radius) {
    this.roamCenter.copy(center)
    this.roamRadius = radius
  }

  setLanding(value) {
    this.landing = value
  }

  update(elapsedSec) {
    const roam = this.scratchRoam
    this.targets.forEach((target, index) => {
      const phase = index * GOLDEN_ANGLE
      const speed = 0.55 + (index % 5) * 0.09
      roam.set(
        Math.cos(elapsedSec * speed + phase),
        Math.sin(elapsedSec * speed * 1.3 + phase) * 0.55,
        Math.sin(elapsedSec * speed * 0.7 + phase * 2) * 0.6
      )
      roam.multiplyScalar(this.roamRadius).add(this.roamCenter)

      const delay = index * LANDING_STAGGER
      const local = Math.min(1, Math.max(0, (this.landing - delay) / (1 - LANDING_STAGGER * this.targets.length)))
      const landed = easeInOutCubic(local)

      this.dummy.position.lerpVectors(roam, target.position, landed)
      const velocity = this.scratchVelocity.subVectors(this.dummy.position, this.previous[index])
      const flightAngle = Math.atan2(velocity.y, velocity.x)
      this.dummy.rotation.set(0, 0, flightAngle + (target.angle - flightAngle) * landed)
      // A landed bee dissolves into its box so the real bee in the keyframe stays visible.
      const presence = 1 - Math.min(1, Math.max(0, (landed - DISSOLVE_START) / (1 - DISSOLVE_START)))
      const size = Math.max(0.0001, target.length * presence)
      this.dummy.scale.set(size, size * 0.55, size * 0.55)
      this.dummy.updateMatrix()
      this.mesh.setMatrixAt(index, this.dummy.matrix)
      this.previous[index].copy(this.dummy.position)
    })
    this.mesh.instanceMatrix.needsUpdate = true
  }

  dispose() {
    this.geometry.dispose()
    this.material.dispose()
  }
}
